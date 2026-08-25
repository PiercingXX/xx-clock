package com.piercingxx.xxclock.audio

/**
 * Pure ordering rule for what a ring should try to play — no `android.*`
 * imports, so the fallback policy is JVM-testable while [KlaxonPlayer] stays a
 * thin edge that just walks the list until a URI actually plays.
 *
 * Order (the "never silent because of a stale pick" rule):
 *  1. the alarm's own chosen tone, when one is set,
 *  2. the system default alarm sound (also covers a chosen URI that no longer
 *     resolves — file deleted, permission revoked, provider gone),
 *  3. the notification default, for devices with no alarm tone configured.
 *
 * Blank entries are dropped and duplicates collapse (a chosen tone that IS the
 * default should not be attempted twice).
 */
fun ringCandidates(
    chosenUri: String?,
    defaultAlarmUri: String?,
    defaultNotificationUri: String?,
): List<String> =
    listOfNotNull(chosenUri, defaultAlarmUri, defaultNotificationUri)
        .filter { it.isNotBlank() }
        .distinct()
