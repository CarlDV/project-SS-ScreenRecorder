package dev.screenrec.service

/**
 * Decides which form of the ongoing notification to post so the system promotes it to the
 * status bar chip.
 *
 * API 36's Notification.hasPromotableCharacteristics() has two branches, selected by the
 * ui_rich_ongoing platform flag, and they demand opposite things:
 *
 * - flag on:  requestPromotedOngoing && ongoing && title && promotable style && !colorized
 * - flag off: ongoing && title && (ongoing CallStyle || (colorized && promotable style))
 *
 * An app cannot read that flag, and pretending to be a phone call to satisfy both is not on
 * the table. So: post the modern non-colorized form, read back whether the system actually set
 * FLAG_PROMOTED_ONGOING -- which the posting app is explicitly allowed to do -- and if it
 * didn't, switch once to the colorized form that the older branch requires.
 */
class ChipPromotion {

    var colorized: Boolean = false
        private set

    private var switched = false

    /**
     * @param promoted whether the posted notification came back carrying FLAG_PROMOTED_ONGOING
     * @return true if the notification should be re-posted in the newly chosen form
     */
    fun onPostResult(promoted: Boolean): Boolean {
        if (promoted || switched) return false
        switched = true
        colorized = true
        return true
    }
}
