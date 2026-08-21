package cz.vsb.minibank.api.dto;

import java.util.List;

/**
 * Detailed view of a fraud alert including transfer, account history and the analysts' journal.
 *
 * @param notes every note left on this alert, oldest first. A list beside the alert rather than a
 *              field inside {@link AlertInfoDto}, for the reason {@code history} is a list beside
 *              it: the queue carries an {@code AlertQueueItemDto} per row and has no room for a
 *              journal, so a journal that lived on the alert record would be loaded once per row
 *              of every page to be printed nowhere. Empty on an alert nobody has written on, which
 *              is most of them.
 */
public record AlertDetailDto(
        AlertInfoDto alert,
        TransferInfoDto transfer,
        List<HistoryItemDto> history,
        List<AlertNoteDto> notes
) {
}
