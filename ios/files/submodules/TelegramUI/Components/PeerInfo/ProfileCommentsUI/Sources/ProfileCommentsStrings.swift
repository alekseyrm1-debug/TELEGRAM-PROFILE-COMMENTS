import Foundation

/// English strings for Profile Comments.
/// The same keys are listed in `Localizable.strings.additions` (ProfileComments.*) so they can be moved
/// into Telegram/Telegram-iOS/en.lproj/Localizable.strings and PresentationStrings when merged.
public enum ProfileCommentsStrings {
    public static let tab = "Comments"
    public static let title = "Profile Comments"

    public static let tagRecommend = "Recommend"
    public static let tagWorked = "Worked together"
    public static let tagDesigner = "Great designer"
    public static let tagReliable = "Fast & reliable"
    public static let tagCreative = "Creative"
    public static let tagMentor = "Helpful mentor"

    public static let justNow = "just now"
    public static func minutesAgo(_ n: Int) -> String { return "\(n) min ago" }
    public static func hoursAgo(_ n: Int) -> String { return "\(n) h ago" }
    public static let yesterday = "Yesterday"
    public static func daysAgo(_ n: Int) -> String { return "\(n) days ago" }

    public static let leave = "Leave a comment"
    public static func turnedOff(_ name: String) -> String { return "\(name) turned off comments" }
    public static func contactsOnly(_ name: String) -> String { return "Only \(name)'s contacts can comment" }
    public static func cooldown(_ wait: String) -> String { return "You can comment again in \(wait)" }

    public static let you = "You"
    public static let awaiting = "Awaiting approval"
    public static let hiddenLabel = "Hidden from visitors"
    public static func reports(_ n: Int) -> String { return n == 1 ? "1 report" : "\(n) reports" }
    public static func replyTitle(_ name: String, _ date: String) -> String { return "\(name) · author · \(date)" }
    public static let approve = "Approve"
    public static let dismiss = "Dismiss"

    public static func count(_ n: Int) -> String { return n == 1 ? "1 comment" : "\(n) comments" }
    public static let filterAll = "All"
    public static func pendingHeader(_ n: Int) -> String { return "Waiting for approval · \(n)" }
    public static let pendingHint = "Only you see these"
    public static let modePublic = "Public"
    public static let modeApproval = "Approval on"
    public static let modeContacts = "Contacts only"
    public static let modeOff = "Comments off"
    public static let settingsShort = "Settings"
    public static let emptyTitle = "No comments yet"
    public static func emptyVisitor(_ name: String) -> String { return "Be the first to leave a comment on \(name)'s profile." }
    public static let emptyOwner = "When people leave comments on your profile, they will appear here."
    public static func emptyOff(_ name: String) -> String { return "\(name) has turned off comments." }
    public static func rules(_ name: String) -> String { return "One comment per person every 24 hours. Only \(name) can reply. To talk, send a private message." }
    public static func rulesOff(_ name: String) -> String { return "\(name) turned off new comments. " }
    public static func rulesContacts(_ name: String) -> String { return "Only \(name)'s contacts can comment. " }

    public static let reply = "Reply"
    public static let editReply = "Edit Reply"
    public static let deleteReply = "Delete Reply"
    public static let pin = "Pin to Top"
    public static let unpin = "Unpin"
    public static let hide = "Hide from Visitors"
    public static let show = "Show to Visitors"
    public static func message(_ name: String) -> String { return "Message \(name)" }
    public static let copy = "Copy Text"
    public static let react = "Add Reaction"
    public static let report = "Report"
    public static let reported = "Reported"
    public static let delete = "Delete Comment"
    public static let deleteMine = "Delete My Comment"
    public static let decline = "Decline"
    public static let cancel = "Cancel"

    public static let toastApproved = "Comment approved"
    public static let toastDismissed = "Comment dismissed"
    public static let toastDeleted = "Comment deleted"
    public static let toastPinned = "Pinned to the top"
    public static let toastUnpinned = "Comment unpinned"
    public static let toastHidden = "Comment hidden from visitors"
    public static let toastShown = "Comment is visible again"
    public static func toastReported(_ name: String) -> String { return "Thanks. \(name) will review this comment." }
    public static let toastCopied = "Text copied to clipboard"
    public static let toastPosted = "Comment posted"
    public static func toastPending(_ name: String) -> String { return "Sent. It will appear after \(name) approves it." }
    public static let toastReply = "Reply posted"
    public static let toastReplyDeleted = "Reply deleted"
    public static let sampleUser = "This is a sample comment, so there is no chat to open."

    public static func sheetTitle(_ name: String) -> String { return "Comment on \(name)'s profile" }
    public static let sheetSubPublic = "Visible to everyone who opens this profile"
    public static func sheetSubApproval(_ name: String) -> String { return "\(name) will review it before it appears" }
    public static func sheetTags(_ name: String) -> String { return "What describes \(name)? (up to 3)" }
    public static func sheetHint(_ name: String) -> String { return "Write something about working with \(name)" }
    public static func sheetNote(_ name: String) -> String { return "You can leave one comment every 24 hours, and only \(name) can reply. Want to talk?" }
    public static let sheetPost = "Post Comment"
    public static func replyTo(_ name: String) -> String { return "Reply to \(name)" }
    public static func editReplyTo(_ name: String) -> String { return "Edit reply to \(name)" }
    public static func replyHint(_ name: String) -> String { return "Thank \(name) or add context" }
    public static func replyNote(_ name: String) -> String { return "One reply per comment. It appears under \(name)'s comment, and visitors can't reply to it." }
    public static let replyPost = "Post Reply"

    public static let whoHeader = "WHO CAN COMMENT ON MY PROFILE?"
    public static let whoEverybody = "Everybody"
    public static let whoContacts = "My Contacts"
    public static let whoNobody = "Nobody"
    public static let whoInfoEverybody = "Anyone who opens your profile can leave one comment a day."
    public static let whoInfoContacts = "Only people in your contacts can leave a comment on your profile."
    public static let whoInfoNobody = "Existing comments stay visible, but no one can add new ones."
    public static let moderationHeader = "MODERATION"
    public static let approveNew = "Approve New Comments"
    public static let approveInfo = "When this is on, new comments stay hidden until you approve them on your profile."
    public static let howHeader = "HOW COMMENTS WORK"
    public static let how1 = "One comment per person a day"
    public static let how1Sub = "Keeps your profile a wall, not a chat."
    public static let how2 = "Only you can reply"
    public static let how2Sub = "Visitors who want to talk are sent to a private chat."
    public static let how3 = "Pin, hide or delete any comment"
    public static let how3Sub = "Tap a comment on your profile to manage it."
    public static func privacyValue(_ who: String, approval: Bool) -> String { return approval ? "\(who), approval" : who }
}
