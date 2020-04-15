/*
 * Copyright 2020 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.library;

import iLinkBinary.BusinessReject521Decoder;
import iLinkBinary.FTI;
import iLinkBinary.KeepAliveLapsed;
import io.aeron.exceptions.TimeoutException;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.ilink.ILink3Offsets;
import uk.co.real_logic.artio.ilink.ILink3Proxy;
import uk.co.real_logic.artio.ilink.ILink3SessionHandler;
import uk.co.real_logic.artio.ilink.IllegalResponseException;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.TimeUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.function.Consumer;

import static iLinkBinary.KeepAliveLapsed.Lapsed;
import static iLinkBinary.KeepAliveLapsed.NotLapsed;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static uk.co.real_logic.artio.LogTag.ILINK_SESSION;
import static uk.co.real_logic.artio.ilink.AbstractILink3Offsets.MISSING_OFFSET;
import static uk.co.real_logic.artio.ilink.AbstractILink3Parser.BOOLEAN_FLAG_TRUE;
import static uk.co.real_logic.artio.library.ILink3SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.messages.DisconnectReason.FAILED_AUTHENTICATION;
import static uk.co.real_logic.artio.messages.DisconnectReason.LOGOUT;
import static uk.co.real_logic.artio.util.TimeUtil.nanoSecondTimestamp;

/**
 * External users should never rely on this API.
 */
public class InternalILink3Session extends ILink3Session
{
    private static final UnsafeBuffer NO_BUFFER = new UnsafeBuffer();
    private static final long OK_POSITION = Long.MIN_VALUE;

    private final NotAppliedResponse response = new NotAppliedResponse();
    private final Deque<RetransmitRequest> retransmitRequests = new ArrayDeque<>();
    private final CharFormatter unknownMessage = new CharFormatter(
        "Unknown Message,templateId=%s,blockLength=%s,version=%s,seqNum=%s,possRetrans=%s%n");
    private final BusinessReject521Decoder businessReject = new BusinessReject521Decoder();
    private final Consumer<StringBuilder> businessRejectAppendTo = businessReject::appendTo;

    private final ILink3Proxy proxy;
    private final ILink3Offsets offsets;
    private final ILink3SessionConfiguration configuration;
    private final long connectionId;
    private final GatewayPublication outboundPublication;
    private final GatewayPublication inboundPublication;
    private final int libraryId;
    private final LibraryPoller owner;
    private final ILink3SessionHandler handler;
    private final boolean newlyAllocated;
    private final long uuid;

    private InitiateILink3SessionReply initiateReply;

    private State state;
    private long nextRecvSeqNo;
    private long nextSentSeqNo;
    private long retransmitFillSeqNo = NOT_AWAITING_RETRANSMIT;

    private long resendTime;
    private long nextReceiveMessageTimeInMs;
    private long nextSendMessageTimeInMs;
    private boolean backpressuredNotApplied = false;

    private String resendTerminateReason;
    private int resendTerminateErrorCodes;
    private long lastNegotiateRequestTimestamp;
    private long lastEstablishRequestTimestamp;

    public InternalILink3Session(
        final ILink3SessionConfiguration configuration,
        final long connectionId,
        final InitiateILink3SessionReply initiateReply,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final LibraryPoller owner,
        final long uuid,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final boolean newlyAllocated)
    {
        this.configuration = configuration;
        this.connectionId = connectionId;
        this.initiateReply = initiateReply;
        this.outboundPublication = outboundPublication;
        this.inboundPublication = inboundPublication;
        this.libraryId = libraryId;
        this.owner = owner;
        this.handler = configuration.handler();
        this.newlyAllocated = newlyAllocated;

        proxy = new ILink3Proxy(connectionId, outboundPublication.dataPublication());
        offsets = new ILink3Offsets();
        nextSentSeqNo(calculateInitialSequenceNumber(
            lastSentSequenceNumber, configuration.initialSentSequenceNumber()));
        nextRecvSeqNo(calculateInitialSequenceNumber(
            lastReceivedSequenceNumber, configuration.initialReceivedSequenceNumber()));
        state = State.CONNECTED;
        this.uuid = uuid;
    }

    // PUBLIC API

    public long tryClaim(
        final MessageEncoderFlyweight message)
    {
        validateCanSend();

        final long position = proxy.claimILinkMessage(message.sbeBlockLength(), message);

        if (position > 0)
        {
            final int templateId = message.sbeTemplateId();
            final MutableDirectBuffer buffer = message.buffer();
            final int messageOffset = message.offset();

            final int seqNumOffset = offsets.seqNumOffset(templateId);
            if (seqNumOffset != MISSING_OFFSET)
            {
                buffer.putInt(messageOffset + seqNumOffset, (int)nextSentSeqNo++, LITTLE_ENDIAN);
            }

            // NB: possRetrans field does not need to be set because it is always false in this claim API
            // and the false byte is 0, which is what Aeron buffers are initialised to.

            final int sendingTimeEpochOffset = offsets.sendingTimeEpochOffset(templateId);
            if (sendingTimeEpochOffset != MISSING_OFFSET)
            {
                buffer.putLong(messageOffset + sendingTimeEpochOffset, TimeUtil.nanoSecondTimestamp(), LITTLE_ENDIAN);
            }
        }

        return position;
    }

    public void commit()
    {
        proxy.commit();

        sentMessage();
    }

    private void sentMessage()
    {
        nextSendMessageTimeInMs = nextTimeoutInMs();
    }

    public long terminate(final String reason, final int errorCodes)
    {
        validateCanSend();

        return sendTerminate(reason, errorCodes, State.UNBINDING, State.RESEND_TERMINATE);
    }

    private long sendTerminate(
        final String reason, final int errorCodes, final State finalState, final State resendState)
    {
        final long requestTimestamp = TimeUtil.nanoSecondTimestamp();
        final long position = proxy.sendTerminate(
            reason,
            uuid,
            requestTimestamp,
            errorCodes);

        if (position > 0)
        {
            state = finalState;
            resendTerminateReason = null;
            resendTerminateErrorCodes = 0;
        }
        else
        {
            state = resendState;
            resendTerminateReason = reason;
            resendTerminateErrorCodes = errorCodes;
        }

        return position;
    }

    private void validateCanSend()
    {
        final State state = this.state;
        if (state != State.ESTABLISHED && state != State.AWAITING_KEEPALIVE)
        {
            throw new IllegalStateException(
                "State should be ESTABLISHED or AWAITING_KEEPALIVE in order to send but is " + state);
        }
    }

    public long requestDisconnect(final DisconnectReason reason)
    {
        return outboundPublication.saveRequestDisconnect(libraryId, connectionId, reason);
    }

    public long uuid()
    {
        return uuid;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public State state()
    {
        return state;
    }

    public long nextSentSeqNo()
    {
        return nextSentSeqNo;
    }

    public void nextSentSeqNo(final long nextSentSeqNo)
    {
        this.nextSentSeqNo = nextSentSeqNo;
    }

    public long nextRecvSeqNo()
    {
        return nextRecvSeqNo;
    }

    public void nextRecvSeqNo(final long nextRecvSeqNo)
    {
        this.nextRecvSeqNo = nextRecvSeqNo;
    }

    public long retransmitFillSeqNo()
    {
        return retransmitFillSeqNo;
    }

    // END PUBLIC API

    public long nextReceiveMessageTimeInMs()
    {
        return nextReceiveMessageTimeInMs;
    }

    public long nextSendMessageTimeInMs()
    {
        return nextSendMessageTimeInMs;
    }

    private long calculateInitialSequenceNumber(
        final long lastSequenceNumber, final long initialSequenceNumber)
    {
        if (!this.configuration.reEstablishLastSession())
        {
            return 1;
        }

        if (initialSequenceNumber == AUTOMATIC_INITIAL_SEQUENCE_NUMBER)
        {
            if (lastSequenceNumber == Session.UNKNOWN)
            {
                return 1;
            }
            else
            {
                return lastSequenceNumber + 1;
            }
        }
        return initialSequenceNumber;
    }

    private boolean sendNegotiate()
    {
        final long requestTimestamp = TimeUtil.nanoSecondTimestamp();
        final String sessionId = configuration.sessionId();
        final String firmId = configuration.firmId();
        final String canonicalMsg = String.valueOf(requestTimestamp) + '\n' + uuid + '\n' + sessionId + '\n' + firmId;
        System.out.println("canonicalMsg = " + canonicalMsg);
        System.out.println("userKey = " + configuration.userKey());
        final byte[] hMACSignature = calculateHMAC(canonicalMsg);
        System.out.println(Arrays.toString(hMACSignature));
        System.out.println(hMACSignature.length);

        final long position = proxy.sendNegotiate(
            hMACSignature, configuration.accessKeyId(), uuid, requestTimestamp, sessionId, firmId);

        if (position > 0)
        {
            state = State.SENT_NEGOTIATE;
            resendTime = nextTimeoutInMs();
            lastNegotiateRequestTimestamp = requestTimestamp;
            return true;
        }

        return false;
    }

    private boolean sendEstablish()
    {
        final long requestTimestamp = TimeUtil.nanoSecondTimestamp();
        final String sessionId = configuration.sessionId();
        final String firmId = configuration.firmId();
        final String tradingSystemName = configuration.tradingSystemName();
        final String tradingSystemVersion = configuration.tradingSystemVersion();
        final String tradingSystemVendor = configuration.tradingSystemVendor();
        final int keepAliveInterval = configuration.requestedKeepAliveIntervalInMs();
        final String accessKeyId = configuration.accessKeyId();

        final String canonicalMsg = String.valueOf(requestTimestamp) + '\n' + uuid + '\n' + sessionId +
            '\n' + firmId + '\n' + tradingSystemName + '\n' + tradingSystemVersion + '\n' + tradingSystemVendor +
            '\n' + nextSentSeqNo + '\n' + keepAliveInterval;
        final byte[] hMACSignature = calculateHMAC(canonicalMsg);

        final long position = proxy.sendEstablish(hMACSignature,
            accessKeyId,
            tradingSystemName,
            tradingSystemVendor,
            tradingSystemVersion,
            uuid,
            requestTimestamp,
            nextSentSeqNo,
            sessionId,
            firmId,
            keepAliveInterval);

        if (position > 0)
        {
            resendTime = nextTimeoutInMs();
            lastEstablishRequestTimestamp = requestTimestamp;
            state = State.SENT_ESTABLISH;
            return true;
        }

        return false;
    }

    private long nextTimeoutInMs()
    {
        return System.currentTimeMillis() + configuration.requestedKeepAliveIntervalInMs();
    }

    private byte[] calculateHMAC(final String canonicalRequest)
    {
        final String userKey = configuration.userKey();

        try
        {
            final Mac sha256HMAC = getHmac();

            // Decode the key first, since it is base64url encoded
            final byte[] decodedUserKey = Base64.getUrlDecoder().decode(userKey);
            final SecretKeySpec secretKey = new SecretKeySpec(decodedUserKey, "HmacSHA256");
            sha256HMAC.init(secretKey);

            // Calculate HMAC
            return sha256HMAC.doFinal(canonicalRequest.getBytes("UTF-8"));
        }
        catch (final InvalidKeyException | IllegalStateException | UnsupportedEncodingException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    private Mac getHmac()
    {
        try
        {
            return Mac.getInstance("HmacSHA256");
        }
        catch (final NoSuchAlgorithmException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    int poll(final long timeInMs)
    {
        final State state = this.state;
        switch (state)
        {
            case CONNECTED:
                return pollConnected();

            case SENT_NEGOTIATE:
                return pollSentNegotiate(timeInMs);

            case RETRY_NEGOTIATE:
                return pollRetryNegotiate(timeInMs);

            case NEGOTIATED:
                return sendEstablish() ? 1 : 0;

            case RETRY_ESTABLISH:
                return pollRetryEstablish(timeInMs);

            case SENT_ESTABLISH:
                return pollSentEstablish(timeInMs);

            case ESTABLISHED:
                return pollEstablished(timeInMs);

            case AWAITING_KEEPALIVE:
                return pollAwaitingKeepAlive(timeInMs);

            case RESEND_TERMINATE:
                return pollResendTerminate();

            case RESEND_TERMINATE_ACK:
                return pollResendTerminateAck();

            case UNBINDING:
                return pollUnbinding(timeInMs);

            default:
                return 0;
        }
    }

    private int pollUnbinding(final long timeInMs)
    {
        if (timeInMs > nextSendMessageTimeInMs)
        {
            fullyUnbind();
        }
        return 0;
    }

    private int pollResendTerminateAck()
    {
        sendTerminateAck(resendTerminateReason, resendTerminateErrorCodes);
        return 0;
    }

    private int pollResendTerminate()
    {
        terminate(resendTerminateReason, resendTerminateErrorCodes);
        return 0;
    }

    private int pollAwaitingKeepAlive(final long timeInMs)
    {
        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            final int expiry = 2 * configuration.requestedKeepAliveIntervalInMs();
            terminate(expiry + "ms expired without message", 0);
        }
        return 0;
    }

    private int pollEstablished(final long timeInMs)
    {
        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            sendSequence(Lapsed);

            onReceivedMessage();

            this.state = State.AWAITING_KEEPALIVE;
        }
        else if (timeInMs > nextSendMessageTimeInMs)
        {
            sendSequence(NotLapsed);
        }
        return 0;
    }

    private int pollSentEstablish(final long timeInMs)
    {
        if (timeInMs > resendTime)
        {
            if (sendEstablish())
            {
                this.state = State.RETRY_ESTABLISH;
                return 1;
            }
        }
        return 0;
    }

    private int pollRetryEstablish(final long timeInMs)
    {
        if (timeInMs > resendTime)
        {
            onEstablishFailure();
            fullyUnbind();
            return 1;
        }
        return 0;
    }

    private int pollRetryNegotiate(final long timeInMs)
    {
        if (timeInMs > resendTime)
        {
            onNegotiateFailure();
            fullyUnbind();
            return 1;
        }
        return 0;
    }

    private int pollSentNegotiate(final long timeInMs)
    {
        if (timeInMs > resendTime)
        {
            if (sendNegotiate())
            {
                this.state = State.RETRY_NEGOTIATE;
                return 1;
            }
        }
        return 0;
    }

    private int pollConnected()
    {
        if (!configuration.reEstablishLastSession() || newlyAllocated)
        {
            return sendNegotiate() ? 1 : 0;
        }
        else
        {
            return sendEstablish() ? 1 : 0;
        }
    }

    private void onNegotiateFailure()
    {
        initiateReply.onError(new TimeoutException("Timed out: no reply for Negotiate"));
    }

    private void onEstablishFailure()
    {
        initiateReply.onError(new TimeoutException("Timed out: no reply for Establish"));
    }

    private long sendSequence(final KeepAliveLapsed keepAliveIntervalLapsed)
    {
        final long position = proxy.sendSequence(uuid, nextSentSeqNo, FTI.Primary, keepAliveIntervalLapsed);
        if (position > 0)
        {
            sentMessage();
        }

        // Will be retried on next poll if enqueue back pressured.

        return position;
    }

    // EVENT HANDLERS

    public long onNegotiationResponse(
        final long uUID,
        final long requestTimestamp,
        final int secretKeySecureIDExpiration,
        final long previousSeqNo,
        final long previousUUID)
    {
        if (checkBoundaryErrors("Negotiate", uUID, requestTimestamp, lastNegotiateRequestTimestamp))
        {
            return 1;
        }

        state = State.NEGOTIATED;
        sendEstablish();

        return 1;
    }

    private boolean checkBoundaryErrors(
        final String name, final long uUID, final long requestTimestamp, final long expectedRequestTimestamp)
    {
        if (uUID != uuid())
        {
            connectionError(new IllegalResponseException("Invalid " + name + ".uuid=" + uUID + ",expected=" + uuid()));
            return true;
        }

        if (expectedRequestTimestamp != requestTimestamp)
        {
            connectionError(new IllegalResponseException(
                "Invalid " + name + ".requestTimestamp=" + requestTimestamp +
                ",expected=" + expectedRequestTimestamp));
            return true;
        }

        return false;
    }

    public long onNegotiationReject(
        final String reason, final long uUID, final long requestTimestamp, final int errorCodes)
    {
        state = State.NEGOTIATE_REJECTED;
        return onReject(
            uUID,
            requestTimestamp,
            lastNegotiateRequestTimestamp,
            "Negotiate rejected: " + reason,
            errorCodes);
    }

    private void connectionError(final Exception error)
    {
        initiateReply.onError(error);
        initiateReply = null;

        requestDisconnect(FAILED_AUTHENTICATION);
        owner.onUnbind(this);
    }

    public long onEstablishmentAck(
        final long uUID,
        final long requestTimestamp,
        final long nextSeqNo,
        final long previousSeqNo,
        final long previousUUID,
        final int keepAliveInterval,
        final int secretKeySecureIDExpiration)
    {
        if (checkBoundaryErrors("EstablishmentAck", uUID, requestTimestamp, lastEstablishRequestTimestamp))
        {
            return 1;
        }

        state = State.ESTABLISHED;
        initiateReply.onComplete(this);
        nextReceiveMessageTimeInMs = nextSendMessageTimeInMs = nextTimeoutInMs();

        final long nextRecvSeqNo = this.nextRecvSeqNo;
        if (previousUUID == uuid)
        {
            final long impliedNextRecvSeqNo = previousSeqNo + 1;
            if (impliedNextRecvSeqNo > nextRecvSeqNo)
            {
                return onInvalidSequenceNumber(impliedNextRecvSeqNo, impliedNextRecvSeqNo);
            }
        }

        final long position = checkLowSequenceNumberCase(nextSeqNo, nextRecvSeqNo);
        if (position != OK_POSITION)
        {
            return position;
        }

        return 1;
    }

    public long onEstablishmentReject(
        final String reason, final long uUID, final long requestTimestamp, final long nextSeqNo, final int errorCodes)
    {
        state = State.ESTABLISH_REJECTED;
        final String reasonMsg = "Establishment rejected: " + reason + ",nextSeqNo=" + nextSeqNo;
        return onReject(uUID, requestTimestamp, lastEstablishRequestTimestamp, reasonMsg, errorCodes);
    }

    private long onReject(
        final long msgUuid,
        final long msgRequestTimestamp,
        final long expectedRequestTimestamp,
        final String reasonMsg,
        final int errorCodes)
    {
        final StringBuilder msgBuilder = new StringBuilder(reasonMsg);
        if (msgUuid != uuid)
        {
            msgBuilder
                .append("Incorrect uuid=")
                .append(msgUuid)
                .append(",expected=")
                .append(uuid)
                .append(",");
        }
        if (msgRequestTimestamp != expectedRequestTimestamp)
        {
            msgBuilder
                .append("Incorrect requestTimestamp=")
                .append(msgRequestTimestamp)
                .append(",expected=")
                .append(expectedRequestTimestamp)
                .append(",");
        }

        msgBuilder.append(",errorCodes=").append(errorCodes);
        connectionError(new IllegalResponseException(msgBuilder.toString()));

        return 1;
    }

    public long onTerminate(final String reason, final long uUID, final long requestTimestamp, final int errorCodes)
    {
        // We initiated termination
        if (state == State.UNBINDING)
        {
            fullyUnbind();
        }
        // The exchange initiated termination
        else
        {
            sendTerminateAck(reason, errorCodes);
        }

        checkUuid(uUID);

        return 1;
    }

    private void sendTerminateAck(final String reason, final int errorCodes)
    {
        final long position = sendTerminate(reason, errorCodes, State.UNBOUND, State.RESEND_TERMINATE_ACK);
        if (position > 0)
        {
            fullyUnbind();
        }
    }

    private void checkUuid(final long uUID)
    {
        if (uUID != uuid())
        {
            throw new IllegalResponseException("Invalid uuid=" + uUID + ",expected=" + uuid());
        }
    }

    public long onSequence(
        final long uUID, final long nextSeqNo, final FTI fti, final KeepAliveLapsed keepAliveLapsed)
    {
        if (uUID == uuid())
        {
            onReceivedMessage();

            final long position = checkLowSequenceNumberCase(nextSeqNo, nextRecvSeqNo);
            if (position == OK_POSITION)
            {
                // Behaviour for a sequence message is to accept a higher sequence update - this differs from a
                // business message
                nextRecvSeqNo(nextSeqNo);
            }
            else
            {
                return position;
            }

            // Reply to any warning messages to keep the session alive.
            if (keepAliveLapsed == Lapsed)
            {
                sendSequence(NotLapsed);
            }
        }

        return 1;
    }

    private long checkLowSequenceNumberCase(final long seqNo, final long nextRecvSeqNo)
    {
        if (seqNo < nextRecvSeqNo)
        {
            return terminate(String.format(
                "seqNo=%s,expecting=%s",
                seqNo,
                this.nextRecvSeqNo), 0);
        }

        return OK_POSITION;
    }

    public long onNotApplied(final long uUID, final long fromSeqNo, final long msgCount)
    {
        if (uUID != uuid())
        {

        }

        // Don't invoke the handler on the backpressured retry
        if (!backpressuredNotApplied)
        {
            // Stop messages from being sent whilst a retransmit is underway.
            state = State.RETRANSMITTING;
            handler.onNotApplied(fromSeqNo, msgCount, response);
            onReceivedMessage();
        }

        if (response.shouldRetransmit())
        {
            final long position = inboundPublication.saveValidResendRequest(
                uUID,
                connectionId,
                fromSeqNo,
                fromSeqNo + msgCount - 1,
                0,
                NO_BUFFER,
                0,
                0);

            backpressuredNotApplied = Pressure.isBackPressured(position);

            return position;
        }
        else
        {
            final long position = sendSequence(NotLapsed);
            if (position > 0)
            {
                state = State.ESTABLISHED;
            }

            backpressuredNotApplied = Pressure.isBackPressured(position);

            return position;
        }
    }

    void onReplayComplete()
    {
        state = State.ESTABLISHED;
    }

    private void onReceivedMessage()
    {
        nextReceiveMessageTimeInMs = nextTimeoutInMs();
    }

    private void fullyUnbind()
    {
        state = State.UNBOUND;
        requestDisconnect(LOGOUT);
        owner.onUnbind(this);
    }

//    private

    public long onMessage(
        final DirectBuffer buffer, final int offset, final int templateId, final int blockLength, final int version)
    {
        onReceivedMessage();

        if (state == State.ESTABLISHED)
        {
            final long seqNum = offsets.seqNum(templateId, buffer, offset);
            if (seqNum == MISSING_OFFSET)
            {
                return 1;
            }

            final int possRetrans = offsets.possRetrans(templateId, buffer, offset);
            if (possRetrans == BOOLEAN_FLAG_TRUE)
            {
                if (seqNum == retransmitFillSeqNo)
                {
                    return retransmitFilled();
                }

                handler.onBusinessMessage(templateId, buffer, offset, blockLength, version, true);

                return 1;
            }

            final long nextRecvSeqNo = this.nextRecvSeqNo;
            final long position = checkLowSequenceNumberCase(seqNum, nextRecvSeqNo);
            if (position == OK_POSITION)
            {
                if (nextRecvSeqNo == seqNum)
                {
                    nextRecvSeqNo(seqNum + 1);

                    handler.onBusinessMessage(templateId, buffer, offset, blockLength, version, false);

                    return 1;
                }
                else
                {
                    return onInvalidSequenceNumber(seqNum);
                }
            }
            else
            {
                return position;
            }
        }
        else
        {
            final long seqNum = offsets.seqNum(templateId, buffer, offset);
            final boolean possRetrans = offsets.possRetrans(templateId, buffer, offset) == BOOLEAN_FLAG_TRUE;

            if (DebugLogger.isEnabled(ILINK_SESSION))
            {
                unknownMessage.clear()
                    .with(templateId)
                    .with(blockLength)
                    .with(version)
                    .with(seqNum)
                    .with(possRetrans);
                DebugLogger.log(ILINK_SESSION, unknownMessage);

                if (templateId == BusinessReject521Decoder.TEMPLATE_ID)
                {
                    businessReject.wrap(buffer, offset, blockLength, version);
                    DebugLogger.logSbeDecoder(ILINK_SESSION, "> ", businessRejectAppendTo);
                }
            }

            return 1;
        }
    }

    private long onInvalidSequenceNumber(final long seqNum)
    {
        return onInvalidSequenceNumber(seqNum, seqNum + 1);
    }

    private long onInvalidSequenceNumber(final long seqNum, final long newNextRecvSeqNo)
    {
        final long fromSeqNo = nextRecvSeqNo;
        final int totalMsgCount = (int)(seqNum - nextRecvSeqNo);
        final int msgCount = Math.min(totalMsgCount, configuration.retransmitRequestMessageLimit());

        if (retransmitFillSeqNo == NOT_AWAITING_RETRANSMIT)
        {
            final long position = sendRetransmitRequest(fromSeqNo, msgCount);
            if (!Pressure.isBackPressured(position))
            {
                addRemainingRetransmitRequests(fromSeqNo, msgCount, totalMsgCount);
                nextRecvSeqNo(newNextRecvSeqNo);
                retransmitFillSeqNo = fromSeqNo + msgCount - 1;
            }
            return position;
        }
        else
        {
            addRetransmitRequest(fromSeqNo, msgCount);
            addRemainingRetransmitRequests(fromSeqNo, msgCount, totalMsgCount);
            nextRecvSeqNo(newNextRecvSeqNo);

            return 1;
        }
    }

    private long retransmitFilled()
    {
        final RetransmitRequest retransmitRequest = retransmitRequests.peekFirst();
        if (retransmitRequest == null)
        {
            retransmitFillSeqNo = NOT_AWAITING_RETRANSMIT;
        }
        else
        {
            final long fromSeqNo = retransmitRequest.fromSeqNo;
            final int msgCount = retransmitRequest.msgCount;
            final long position = sendRetransmitRequest(fromSeqNo, msgCount);

            if (!Pressure.isBackPressured(position))
            {
                retransmitRequests.pollFirst();
                retransmitFillSeqNo = fromSeqNo + msgCount - 1;
            }

            return position;
        }

        return 1;
    }

    private void addRemainingRetransmitRequests(
        final long initialFromSeqNo, final int initialMessagesRequested, final int totalMessageCount)
    {
        final int retransmitRequestMsgLimit = configuration.retransmitRequestMessageLimit();

        long fromSeqNo = initialFromSeqNo + initialMessagesRequested;
        int messagesRequested = initialMessagesRequested;

        while (messagesRequested < totalMessageCount)
        {
            final int msgCount = Math.min(totalMessageCount - messagesRequested, retransmitRequestMsgLimit);
            addRetransmitRequest(fromSeqNo, msgCount);

            messagesRequested += msgCount;
            fromSeqNo += msgCount;
        }
    }

    private void addRetransmitRequest(final long fromSeqNo, final int msgCount)
    {
        retransmitRequests.offerLast(new RetransmitRequest(fromSeqNo, msgCount));
    }

    private long sendRetransmitRequest(final long fromSeqNo, final int msgCount)
    {
        sentMessage();
        final long requestTimestamp = nanoSecondTimestamp();
        return proxy.sendRetransmitRequest(uuid, requestTimestamp, fromSeqNo, msgCount);
    }

    static final class RetransmitRequest
    {
        final long fromSeqNo;
        final int msgCount;

        RetransmitRequest(final long fromSeqNo, final int msgCount)
        {
            this.fromSeqNo = fromSeqNo;
            this.msgCount = msgCount;
        }
    }

    public long onRetransmitReject(
        final String reason, final long uUID, final long requestTimestamp, final int errorCodes)
    {
        checkUuid(uUID);

        handler.onRetransmitReject(reason, requestTimestamp, errorCodes);

        retransmitFilled();

        return 1;
    }
}
