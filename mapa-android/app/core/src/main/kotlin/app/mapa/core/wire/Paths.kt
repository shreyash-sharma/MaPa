package app.mapa.core.wire

/**
 * Firestore layout, shared by both apps. backend/firestore.rules and backend/functions
 * mirror these names; change them together.
 *
 * parents/{parentId}                    profile: name, role, language, zone, paused, consentAt
 *   status/current                      latest StatusWire (derived state only)
 *   days/{yyyy-MM-dd}                   today's activity bins (-1/0/1) and usual band
 *   events/{auto}                       state changes and alerts ("Why did this change?")
 *   members/{uid}                       family members the parent approved via invite
 *   tags/{auto}                         life tags from parent or family
 *   routine/current                     pending new-routine question and the family's answer
 *   trends/{yyyy-MM-dd}                 weekly rhythm trend
 * invites/{code}                        parentId, expiresAt (get-only, never listable)
 * users/{uid}/links/{parentId}          which parents a family member follows
 * users/{uid}/tokens/{fcmToken}         push tokens
 */
object Paths {
    const val PARENTS = "parents"
    const val STATUS = "status"
    const val STATUS_DOC = "current"
    const val DAYS = "days"
    const val EVENTS = "events"
    const val MEMBERS = "members"
    const val TAGS = "tags"
    const val ROUTINE = "routine"
    const val ROUTINE_DOC = "current"
    const val TRENDS = "trends"
    const val INVITES = "invites"
    /** Family-started setup: the family member creates it, the parent accepts it. */
    const val PAIRINGS = "pairings"
    const val USERS = "users"
    const val LINKS = "links"
    const val TOKENS = "tokens"
}
