package cz.vsb.minibank.api.dto.fraud;

/**
 * One entry of an alert's notes journal on the wire.
 *
 * The alert is not on it: these arrive inside {@link AlertDetailDto}, which is one alert, so
 * repeating its id on every row would be noise a screen has to skip past.
 *
 * @param author    who wrote it, or null on the entry carried over from the single notes column
 *                  the journal replaced, which recorded no author. A screen shows that as unknown
 *                  rather than as nobody's note
 * @param writtenAt when they wrote it, as an ISO instant, like every other instant on this API
 * @param text      what they wrote, never null and never blank
 */
public record AlertNoteDto(
        String author,
        String writtenAt,
        String text
) {
}
