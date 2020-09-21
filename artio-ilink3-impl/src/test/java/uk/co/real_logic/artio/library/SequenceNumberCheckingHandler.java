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

import iLinkBinary.ExecutionReportStatus532Decoder;
import org.agrona.DirectBuffer;
import org.agrona.collections.LongArrayList;
import uk.co.real_logic.artio.ilink.ILink3ConnectionHandler;

public class SequenceNumberCheckingHandler implements ILink3ConnectionHandler
{
    private final ExecutionReportStatus532Decoder executionReportStatus = new ExecutionReportStatus532Decoder();
    private final LongArrayList sequenceNumbers = new LongArrayList();

    public void onBusinessMessage(
        final ILink3Connection connection,
        final int templateId,
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final boolean possRetrans)
    {
        if (templateId == ExecutionReportStatus532Decoder.TEMPLATE_ID)
        {
            executionReportStatus.wrap(buffer, offset, blockLength, version);
            sequenceNumbers.add(executionReportStatus.seqNum());
        }
    }

    public LongArrayList sequenceNumbers()
    {
        return sequenceNumbers;
    }

    public void onNotApplied(
        final ILink3Connection connection,
        final long fromSequenceNumber,
        final long msgCount,
        final NotAppliedResponse response)
    {
    }

    public void onRetransmitReject(
        final ILink3Connection connection,
        final String reason,
        final long lastUuid,
        final long requestTimestamp,
        final int errorCodes)
    {
    }

    public void onSequence(final ILink3Connection connection, final long uuid, final long nextSeqNo)
    {
    }

    public void onError(final ILink3Connection connection, final Exception ex)
    {
    }

    public void onDisconnect(final ILink3Connection connection)
    {
    }
}