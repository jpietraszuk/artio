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
package uk.co.real_logic.artio.ilink;

import iLinkBinary.Establish503Encoder;
import iLinkBinary.Negotiate500Encoder;
import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.MutableDirectBuffer;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.messages.ILinkMessageEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;

import static uk.co.real_logic.artio.ilink.SimpleOpenFramingHeader.SOFH_LENGTH;
import static uk.co.real_logic.artio.ilink.SimpleOpenFramingHeader.writeSofh;

public class ILink3Proxy extends AbstractILink3Proxy
{
    public static final int ILINK_HEADER_LENGTH = SOFH_LENGTH + iLinkBinary.MessageHeaderEncoder.ENCODED_LENGTH;

    private static final int ILINK_MESSAGE_HEADER = ARTIO_HEADER_LENGTH + ILINK_HEADER_LENGTH;

    private final ILinkMessageEncoder iLinkMessage = new ILinkMessageEncoder();
    private final BufferClaim bufferClaim = new BufferClaim();

    private final long connectionId;
    private final ExclusivePublication publication;
    private final MessageHeaderEncoder messageHeader = new MessageHeaderEncoder();
    private final iLinkBinary.MessageHeaderEncoder iLinkMessageHeader = new iLinkBinary.MessageHeaderEncoder();

    private final Negotiate500Encoder negotiate = new Negotiate500Encoder();
    private final Establish503Encoder establish = new Establish503Encoder();

    public ILink3Proxy(final long connectionId, final ExclusivePublication publication)
    {
        this.connectionId = connectionId;

        this.publication = publication;
    }

    public long sendNegotiate(
        final byte[] hMACSignature,
        final String accessKeyId,
        final long uuid,
        final long requestTimestamp,
        final String sessionId,
        final String firmId)
    {
        final Negotiate500Encoder negotiate = this.negotiate;

        final long position = claimILinkMessage(Negotiate500Encoder.BLOCK_LENGTH, negotiate);
        if (position < 0)
        {
            return position;
        }

        negotiate
            .putHMACSignature(hMACSignature, 0)
            .accessKeyID(accessKeyId)
            .uUID(uuid)
            .requestTimestamp(requestTimestamp)
            .session(sessionId)
            .firm(firmId);

        bufferClaim.commit();

        return position;
    }

    public long sendEstablish(
        final byte[] hMACSignature,
        final String accessKeyId,
        final String tradingSystemName,
        final String tradingSystemVendor,
        final String tradingSystemVersion,
        final long uuid,
        final long requestTimestamp,
        final int nextSentSeqNo,
        final String sessionId,
        final String firmId,
        final int keepAliveInterval)
    {
        final Establish503Encoder establish = this.establish;

        final long position = claimILinkMessage(Establish503Encoder.BLOCK_LENGTH, establish);
        if (position < 0)
        {
            return position;
        }

        establish
            .putHMACSignature(hMACSignature, 0)
            .accessKeyID(accessKeyId)
            .tradingSystemName(tradingSystemName)
            .tradingSystemVendor(tradingSystemVendor)
            .tradingSystemVersion(tradingSystemVersion)
            .uUID(uuid)
            .requestTimestamp(requestTimestamp)
            .nextSeqNo(nextSentSeqNo)
            .session(sessionId)
            .firm(firmId)
            .keepAliveInterval(keepAliveInterval);

        bufferClaim.commit();

        return position;
    }

    public long claimILinkMessage(
        final int messageLength,
        final MessageEncoderFlyweight message)
    {
        final BufferClaim bufferClaim = this.bufferClaim;
        final long position = publication.tryClaim(ILINK_MESSAGE_HEADER + messageLength, bufferClaim);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        int offset = bufferClaim.offset();

        iLinkMessage
            .wrapAndApplyHeader(buffer, offset, messageHeader)
            .connection(connectionId);

        offset += ARTIO_HEADER_LENGTH;

        writeSofh(buffer, offset, ILINK_HEADER_LENGTH + messageLength);
        offset += SOFH_LENGTH;

        iLinkMessageHeader
            .wrap(buffer, offset)
            .blockLength(message.sbeBlockLength())
            .templateId(message.sbeTemplateId())
            .schemaId(message.sbeSchemaId())
            .version(message.sbeSchemaVersion());

        offset += iLinkMessageHeader.encodedLength();

        message.wrap(buffer, offset);

        return position;
    }

}