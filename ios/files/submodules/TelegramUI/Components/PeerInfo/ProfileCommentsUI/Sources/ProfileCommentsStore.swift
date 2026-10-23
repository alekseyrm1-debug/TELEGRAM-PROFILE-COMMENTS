import Foundation
import SwiftSignalKit
import TelegramCore

/// Profile Comments: a moderated comment wall on a user's profile. It is a wall, not a chat.
///
/// Rules:
/// - one comment per person every 24 hours;
/// - only the profile owner can reply (one reply per comment, shown under it);
/// - visitors can't reply to each other, "Message" opens a private chat instead;
/// - the owner chooses who can comment (Everybody / My Contacts / Nobody), can require approval,
///   and can pin, hide or delete any comment. Visitors can report a comment.
///
/// The store keeps a per-account cache in UserDefaults. Every mutating method maps 1:1 to a method
/// of the proposed MTProto API (docs/API.md, `profileComments.*`). Until the server side exists the
/// cache is the source of truth, which is enough to run and demo the whole flow.
public final class ProfileCommentsStore {
    /// Seeds sample comments on your own wall so the feature can be demoed on a fresh account.
    public static var demoData = true

    public static let cooldown: TimeInterval = 24 * 60 * 60
    public static let maxLength = 300
    public static let minLength = 2
    public static let maxTags = 3
    public static let reactions: [String] = ["❤️", "👍", "🔥", "🥰", "👏", "😁", "🎉", "🤩", "😮", "🙏", "💯", "👌"]

    public struct Tag: Equatable {
        public let id: String
        public let emoji: String
        public let title: String
    }

    public static let tags: [Tag] = [
        Tag(id: "recommend", emoji: "👍", title: ProfileCommentsStrings.tagRecommend),
        Tag(id: "worked", emoji: "🤝", title: ProfileCommentsStrings.tagWorked),
        Tag(id: "designer", emoji: "🎨", title: ProfileCommentsStrings.tagDesigner),
        Tag(id: "reliable", emoji: "⚡", title: ProfileCommentsStrings.tagReliable),
        Tag(id: "creative", emoji: "✨", title: ProfileCommentsStrings.tagCreative),
        Tag(id: "mentor", emoji: "🧭", title: ProfileCommentsStrings.tagMentor)
    ]

    public static func tag(id: String) -> Tag? {
        return self.tags.first(where: { $0.id == id })
    }

    public enum Who: Int32, Codable {
        case everybody = 0
        case contacts = 1
        case nobody = 2
    }

    public enum Status: Int32, Codable {
        case approved = 0
        case pending = 1
        case hidden = 2
    }

    public struct Settings: Codable, Equatable {
        public var who: Who = .everybody
        public var approve: Bool = false

        public init() {
        }
    }

    public struct Comment: Codable, Equatable {
        public var id: Int64
        public var authorId: Int64
        /// Used when the author isn't known locally (and for demo data).
        public var authorName: String?
        public var text: String
        public var date: Double
        public var tags: [String]
        public var status: Status
        public var pinned: Bool
        public var replyText: String?
        public var replyDate: Double?
        /// emoji -> user ids
        public var reactions: [String: [Int64]]
        public var reports: [Int64]

        public var hasReply: Bool {
            return !(self.replyText ?? "").isEmpty
        }

        public func reactionCount(_ emoji: String) -> Int {
            return self.reactions[emoji]?.count ?? 0
        }

        /// Reactions with at least one user, most popular first.
        public var sortedReactions: [String] {
            return self.reactions.filter { !$0.value.isEmpty }.sorted(by: { lhs, rhs in
                if lhs.value.count != rhs.value.count {
                    return lhs.value.count > rhs.value.count
                }
                return lhs.key < rhs.key
            }).map { $0.key }
        }
    }

    public enum LeaveState: Equatable {
        case owner
        case allowed
        case disabled(String)
    }

    private static var instances: [Int64: ProfileCommentsStore] = [:]
    private static let instancesLock = NSLock()

    /// Safe to call from any thread: PeerInfoData asks for tab visibility off the main queue.
    public static func shared(accountPeerId: EnginePeer.Id) -> ProfileCommentsStore {
        self.instancesLock.lock()
        defer {
            self.instancesLock.unlock()
        }
        let key = accountPeerId.toInt64()
        if let current = self.instances[key] {
            return current
        }
        let store = ProfileCommentsStore(accountPeerId: accountPeerId)
        self.instances[key] = store
        return store
    }

    public let accountPeerId: EnginePeer.Id
    private let defaults: UserDefaults
    private var walls: [Int64: [Comment]] = [:]
    private var settingsCache: [Int64: Settings] = [:]
    private let updatesPipe = ValuePipe<EnginePeer.Id>()
    private let lock = NSRecursiveLock()

    /// Emits the owner id whenever that wall or its settings change.
    public var updates: Signal<EnginePeer.Id, NoError> {
        return self.updatesPipe.signal()
    }

    private init(accountPeerId: EnginePeer.Id) {
        self.accountPeerId = accountPeerId
        self.defaults = UserDefaults(suiteName: "profile_comments_\(accountPeerId.toInt64())") ?? .standard
    }

    // MARK: - Identity

    public func isOwner(_ ownerId: EnginePeer.Id) -> Bool {
        return ownerId == self.accountPeerId
    }

    public func isMine(_ comment: Comment) -> Bool {
        return comment.authorId == self.accountPeerId.toInt64()
    }

    /// Whether the Comments tab should appear on this profile.
    public func isTabVisible(ownerId: EnginePeer.Id) -> Bool {
        if self.isOwner(ownerId) {
            return true
        }
        return self.approvedCount(ownerId: ownerId) > 0 || self.settings(ownerId: ownerId).who != .nobody
    }

    // MARK: - Settings

    public func settings(ownerId: EnginePeer.Id) -> Settings {
        self.lock.lock()
        defer {
            self.lock.unlock()
        }
        let key = ownerId.toInt64()
        if let current = self.settingsCache[key] {
            return current
        }
        // TODO(server): visitors get these values from userFull.profile_comments_settings.
        var value = Settings()
        if let data = self.defaults.data(forKey: "settings_\(key)"), let decoded = try? JSONDecoder().decode(Settings.self, from: data) {
            value = decoded
        }
        self.settingsCache[key] = value
        return value
    }

    /// account.setProfileCommentsSettings
    public func updateMySettings(_ f: (inout Settings) -> Void) {
        let key = self.accountPeerId.toInt64()
        self.lock.lock()
        var value = self.settings(ownerId: self.accountPeerId)
        f(&value)
        self.settingsCache[key] = value
        if let data = try? JSONEncoder().encode(value) {
            self.defaults.set(data, forKey: "settings_\(key)")
        }
        self.lock.unlock()
        self.updatesPipe.putNext(self.accountPeerId)
    }

    // MARK: - Reading

    /// profileComments.getComments
    public func all(ownerId: EnginePeer.Id) -> [Comment] {
        self.lock.lock()
        defer {
            self.lock.unlock()
        }
        let key = ownerId.toInt64()
        if let current = self.walls[key] {
            return current
        }
        var list: [Comment] = []
        if let data = self.defaults.data(forKey: "wall_\(key)"), let decoded = try? JSONDecoder().decode([Comment].self, from: data) {
            list = decoded
            self.walls[key] = list
        } else if ProfileCommentsStore.demoData && self.isOwner(ownerId) {
            list = ProfileCommentsStore.demoComments()
            self.walls[key] = list
            self.save(ownerId: ownerId)
        } else {
            self.walls[key] = list
        }
        return list
    }

    /// What the current user sees: newest first, pinned on top. The owner sees everything except the
    /// pending queue (shown separately); visitors see approved comments and their own pending one.
    public func visible(ownerId: EnginePeer.Id, tagFilter: String?) -> [Comment] {
        let owner = self.isOwner(ownerId)
        var result = self.all(ownerId: ownerId).filter { comment in
            if owner {
                return comment.status != .pending
            } else {
                return comment.status == .approved || (comment.status == .pending && self.isMine(comment))
            }
        }
        if let tagFilter {
            result = result.filter { $0.tags.contains(tagFilter) }
        }
        result.sort(by: { lhs, rhs in
            let lp = lhs.pinned && lhs.status == .approved
            let rp = rhs.pinned && rhs.status == .approved
            if lp != rp {
                return lp
            }
            return lhs.date > rhs.date
        })
        return result
    }

    public func pending(ownerId: EnginePeer.Id) -> [Comment] {
        guard self.isOwner(ownerId) else {
            return []
        }
        return self.all(ownerId: ownerId).filter { $0.status == .pending }.sorted(by: { $0.date > $1.date })
    }

    public func approvedCount(ownerId: EnginePeer.Id) -> Int {
        return self.all(ownerId: ownerId).filter { $0.status == .approved }.count
    }

    /// Tag id and number of approved comments with it, most used first.
    public func tagCounts(ownerId: EnginePeer.Id) -> [(String, Int)] {
        var counts: [String: Int] = [:]
        for comment in self.all(ownerId: ownerId) where comment.status == .approved {
            for tag in comment.tags where ProfileCommentsStore.tag(id: tag) != nil {
                counts[tag, default: 0] += 1
            }
        }
        return counts.sorted(by: { lhs, rhs in
            if lhs.value != rhs.value {
                return lhs.value > rhs.value
            }
            return lhs.key < rhs.key
        }).map { ($0.key, $0.value) }
    }

    private func myRecent(ownerId: EnginePeer.Id) -> Comment? {
        let since = Date().timeIntervalSince1970 - ProfileCommentsStore.cooldown
        return self.all(ownerId: ownerId).filter { self.isMine($0) && $0.date > since }.max(by: { $0.date < $1.date })
    }

    /// - Parameter ownerIsMutualContact: whether the owner has us in contacts (needed for "My Contacts").
    public func leaveState(ownerId: EnginePeer.Id, ownerFirstName: String, ownerIsMutualContact: Bool) -> LeaveState {
        if self.isOwner(ownerId) {
            return .owner
        }
        let settings = self.settings(ownerId: ownerId)
        switch settings.who {
        case .nobody:
            return .disabled(ProfileCommentsStrings.turnedOff(ownerFirstName))
        case .contacts where !ownerIsMutualContact:
            return .disabled(ProfileCommentsStrings.contactsOnly(ownerFirstName))
        default:
            break
        }
        if let recent = self.myRecent(ownerId: ownerId) {
            let left = recent.date + ProfileCommentsStore.cooldown - Date().timeIntervalSince1970
            return .disabled(ProfileCommentsStrings.cooldown(ProfileCommentsStore.formatWait(left)))
        }
        return .allowed
    }

    // MARK: - Writing

    /// profileComments.postComment
    @discardableResult
    public func post(ownerId: EnginePeer.Id, text: String, tags: [String], ownerFirstName: String, ownerIsMutualContact: Bool) -> Comment? {
        guard case .allowed = self.leaveState(ownerId: ownerId, ownerFirstName: ownerFirstName, ownerIsMutualContact: ownerIsMutualContact) else {
            return nil
        }
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= ProfileCommentsStore.minLength else {
            return nil
        }
        if text.count > ProfileCommentsStore.maxLength {
            text = String(text.prefix(ProfileCommentsStore.maxLength))
        }
        var cleanTags: [String] = []
        for tag in tags where ProfileCommentsStore.tag(id: tag) != nil && !cleanTags.contains(tag) && cleanTags.count < ProfileCommentsStore.maxTags {
            cleanTags.append(tag)
        }
        let now = Date().timeIntervalSince1970
        let comment = Comment(
            id: Int64(now * 1000.0),
            authorId: self.accountPeerId.toInt64(),
            authorName: nil,
            text: text,
            date: now,
            tags: cleanTags,
            status: self.settings(ownerId: ownerId).approve ? .pending : .approved,
            pinned: false,
            replyText: nil,
            replyDate: nil,
            reactions: [:],
            reports: []
        )
        self.lock.lock()
        var list = self.all(ownerId: ownerId)
        list.append(comment)
        self.walls[ownerId.toInt64()] = list
        self.lock.unlock()
        self.commit(ownerId: ownerId)
        return comment
    }

    private func modify(ownerId: EnginePeer.Id, commentId: Int64, _ f: (inout Comment) -> Void) {
        self.lock.lock()
        var list = self.all(ownerId: ownerId)
        guard let index = list.firstIndex(where: { $0.id == commentId }) else {
            self.lock.unlock()
            return
        }
        f(&list[index])
        self.walls[ownerId.toInt64()] = list
        self.lock.unlock()
        self.commit(ownerId: ownerId)
    }

    /// profileComments.setReply (owner only; empty text removes the reply)
    public func setReply(ownerId: EnginePeer.Id, commentId: Int64, text: String?) {
        guard self.isOwner(ownerId) else {
            return
        }
        var value = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if value.count > ProfileCommentsStore.maxLength {
            value = String(value.prefix(ProfileCommentsStore.maxLength))
        }
        self.modify(ownerId: ownerId, commentId: commentId) { comment in
            comment.replyText = value.isEmpty ? nil : value
            comment.replyDate = Date().timeIntervalSince1970
        }
    }

    /// profileComments.setStatus (owner only): approve, hide or show again
    public func setStatus(ownerId: EnginePeer.Id, commentId: Int64, status: Status) {
        guard self.isOwner(ownerId) else {
            return
        }
        self.modify(ownerId: ownerId, commentId: commentId) { comment in
            comment.status = status
            if status != .approved {
                comment.pinned = false
            }
        }
    }

    /// profileComments.togglePin (owner only). One pinned comment at a time. Returns the new state.
    @discardableResult
    public func togglePin(ownerId: EnginePeer.Id, commentId: Int64) -> Bool {
        guard self.isOwner(ownerId) else {
            return false
        }
        self.lock.lock()
        var list = self.all(ownerId: ownerId)
        guard let index = list.firstIndex(where: { $0.id == commentId }), list[index].status == .approved else {
            self.lock.unlock()
            return false
        }
        let pin = !list[index].pinned
        for i in 0 ..< list.count {
            list[i].pinned = false
        }
        list[index].pinned = pin
        self.walls[ownerId.toInt64()] = list
        self.lock.unlock()
        self.commit(ownerId: ownerId)
        return pin
    }

    /// profileComments.deleteComment (owner: any comment, visitor: own comment)
    public func delete(ownerId: EnginePeer.Id, commentId: Int64) {
        self.lock.lock()
        var list = self.all(ownerId: ownerId)
        guard let index = list.firstIndex(where: { $0.id == commentId }), self.isOwner(ownerId) || self.isMine(list[index]) else {
            self.lock.unlock()
            return
        }
        list.remove(at: index)
        self.walls[ownerId.toInt64()] = list
        self.lock.unlock()
        self.commit(ownerId: ownerId)
    }

    /// profileComments.report (visitors)
    public func report(ownerId: EnginePeer.Id, commentId: Int64) {
        let me = self.accountPeerId.toInt64()
        self.modify(ownerId: ownerId, commentId: commentId) { comment in
            if comment.authorId != me && !comment.reports.contains(me) {
                comment.reports.append(me)
            }
        }
    }

    public func isReportedByMe(_ comment: Comment) -> Bool {
        return comment.reports.contains(self.accountPeerId.toInt64())
    }

    public func hasMyReaction(_ comment: Comment, emoji: String) -> Bool {
        return comment.reactions[emoji]?.contains(self.accountPeerId.toInt64()) ?? false
    }

    /// profileComments.sendReaction. Returns true when the reaction was added.
    @discardableResult
    public func toggleReaction(ownerId: EnginePeer.Id, commentId: Int64, emoji: String) -> Bool {
        let me = self.accountPeerId.toInt64()
        var added = false
        self.modify(ownerId: ownerId, commentId: commentId) { comment in
            guard comment.status == .approved else {
                return
            }
            var users = comment.reactions[emoji] ?? []
            if let index = users.firstIndex(of: me) {
                users.remove(at: index)
            } else {
                users.append(me)
                added = true
            }
            comment.reactions[emoji] = users
        }
        return added
    }

    private func commit(ownerId: EnginePeer.Id) {
        self.save(ownerId: ownerId)
        self.updatesPipe.putNext(ownerId)
    }

    private func save(ownerId: EnginePeer.Id) {
        self.lock.lock()
        defer {
            self.lock.unlock()
        }
        let key = ownerId.toInt64()
        guard let list = self.walls[key], let data = try? JSONEncoder().encode(list) else {
            return
        }
        self.defaults.set(data, forKey: "wall_\(key)")
    }

    // MARK: - Formatting

    public static func formatAgo(_ date: Double) -> String {
        let now = Date()
        let seconds = now.timeIntervalSince1970 - date
        if seconds < 60 {
            return ProfileCommentsStrings.justNow
        }
        if seconds < 3600 {
            return ProfileCommentsStrings.minutesAgo(Int(seconds / 60))
        }
        let calendar = Calendar.current
        let then = Date(timeIntervalSince1970: date)
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: then), to: calendar.startOfDay(for: now)).day ?? 0
        if days <= 0 {
            return ProfileCommentsStrings.hoursAgo(Int(seconds / 3600))
        }
        if days == 1 {
            return ProfileCommentsStrings.yesterday
        }
        if days < 7 {
            return ProfileCommentsStrings.daysAgo(days)
        }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate(calendar.component(.year, from: then) == calendar.component(.year, from: now) ? "MMM d" : "MMM d, yyyy")
        return formatter.string(from: then)
    }

    public static func formatWait(_ seconds: TimeInterval) -> String {
        let minutes = max(1, Int(ceil(seconds / 60.0)))
        let h = minutes / 60
        let m = minutes % 60
        if h > 0 {
            return m > 0 ? "\(h) h \(m) min" : "\(h) h"
        }
        return "\(m) min"
    }

    public static func tagLine(_ comment: Comment) -> String {
        return comment.tags.compactMap { id -> String? in
            guard let tag = ProfileCommentsStore.tag(id: id) else {
                return nil
            }
            return "\(tag.emoji) \(tag.title)"
        }.joined(separator: "  ·  ")
    }

    public static func whoTitle(_ who: Who) -> String {
        switch who {
        case .everybody:
            return ProfileCommentsStrings.whoEverybody
        case .contacts:
            return ProfileCommentsStrings.whoContacts
        case .nobody:
            return ProfileCommentsStrings.whoNobody
        }
    }

    // MARK: - Demo

    private static func demoComments() -> [Comment] {
        let now = Date().timeIntervalSince1970
        let day = ProfileCommentsStore.cooldown
        func make(_ n: Int64, _ name: String, _ text: String, _ date: Double, _ status: Status, _ tags: [String], _ emoji: String?, _ count: Int) -> Comment {
            var reactions: [String: [Int64]] = [:]
            if let emoji, count > 0 {
                reactions[emoji] = (0 ..< count).map { -10 - Int64($0) }
            }
            // Negative ids never collide with real users and are treated as "not known locally".
            return Comment(id: n, authorId: -1000 - n, authorName: name, text: text, date: date, tags: tags, status: status, pinned: false, replyText: nil, replyDate: nil, reactions: reactions, reports: [])
        }
        var noah = make(2, "Noah Bennett", "Super reliable designer. Delivered the whole onboarding flow two days early.", now - day - 3000, .approved, ["recommend", "reliable"], "👍", 4)
        noah.pinned = true
        noah.replyText = "Thanks Noah, that project was a joy 🙌"
        noah.replyDate = now - day
        var emma = make(4, "Emma Wilson", "The mockup you sent yesterday turned out so good. We shipped it to the whole team!", now - 20 * 60, .approved, ["worked", "recommend", "designer"], "🔥", 3)
        emma.reactions["❤️"] = [-1]
        return [
            make(1, "Sofia Martinez", "Hi from Barcelona! Saw the prototype you're building, it looks amazing 🎉", now - 4 * day, .approved, ["creative"], "🎉", 2),
            noah,
            make(3, "Liam Brooks", "Thanks for the talk in the design group. The part about prototyping with real code changed how I work.", now - 3 * 3600, .approved, ["mentor"], "👍", 1),
            emma,
            make(5, "Olivia Hayes", "Can you share the Figma file? Would love to learn from it.", now - 6 * 60, .pending, [], nil, 0),
            make(6, "Alex Morgan", "Rebuilt our landing page in two days. Clean files and great communication! 🔥", now - 2 * 60, .pending, ["recommend", "worked"], nil, 0)
        ]
    }
}
