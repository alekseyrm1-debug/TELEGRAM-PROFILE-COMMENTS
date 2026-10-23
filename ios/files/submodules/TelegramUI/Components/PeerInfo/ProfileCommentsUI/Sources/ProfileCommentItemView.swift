import Foundation
import UIKit
import Display
import TelegramCore
import TelegramPresentationData
import AccountContext
import AvatarNode

/// One comment on the wall: author, tags, text, reactions and the owner's reply.
final class ProfileCommentItemView: UIView {
    struct Item {
        let comment: ProfileCommentsStore.Comment
        let authorPeer: EnginePeer?
        let authorName: String
        let ownerPeer: EnginePeer?
        let ownerName: String
        let isOwnerView: Bool
        let isMine: Bool
        let isPendingSection: Bool
        let myReactions: Set<String>
        let showDivider: Bool
    }

    var onTap: ((ProfileCommentItemView) -> Void)?
    var onDoubleTap: ((ProfileCommentItemView) -> Void)?
    var onReaction: ((String) -> Void)?
    var onApprove: (() -> Void)?
    var onDismiss: (() -> Void)?

    private(set) var item: Item?

    private let avatarNode = AvatarNode(font: avatarPlaceholderFont(size: 16.0))
    private let nameLabel = UILabel()
    private let pinView = UIImageView()
    private let dateLabel = UILabel()
    private let tagsLabel = UILabel()
    private let statusLabel = PaddedLabel()
    private let textLabel = UILabel()
    private var reactionButtons: [UIButton] = []
    private let replyView = UIView()
    private let replyBar = UIView()
    private let replyAvatarNode = AvatarNode(font: avatarPlaceholderFont(size: 8.0))
    private let replyTitleLabel = UILabel()
    private let replyTextLabel = UILabel()
    private let approveButton = UIButton(type: .system)
    private let dismissButton = UIButton(type: .system)
    private let divider = UIView()
    private var bumpEmoji: String?

    override init(frame: CGRect) {
        super.init(frame: frame)

        self.addSubview(self.avatarNode.view)

        self.nameLabel.font = Font.semibold(15.0)
        self.nameLabel.lineBreakMode = .byTruncatingTail
        self.addSubview(self.nameLabel)

        self.pinView.image = UIImage(systemName: "pin.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 11.0, weight: .semibold))
        self.pinView.contentMode = .center
        self.addSubview(self.pinView)

        self.dateLabel.font = Font.regular(13.0)
        self.addSubview(self.dateLabel)

        self.tagsLabel.font = Font.regular(13.0)
        self.tagsLabel.numberOfLines = 0
        self.addSubview(self.tagsLabel)

        self.statusLabel.font = Font.semibold(12.0)
        self.statusLabel.layer.cornerRadius = 6.0
        self.statusLabel.clipsToBounds = true
        self.addSubview(self.statusLabel)

        self.textLabel.font = Font.regular(15.0)
        self.textLabel.numberOfLines = 0
        self.addSubview(self.textLabel)

        self.replyView.layer.cornerRadius = 8.0
        self.replyView.clipsToBounds = true
        self.replyView.addSubview(self.replyBar)
        self.replyView.addSubview(self.replyAvatarNode.view)
        self.replyTitleLabel.font = Font.semibold(13.0)
        self.replyTitleLabel.lineBreakMode = .byTruncatingTail
        self.replyView.addSubview(self.replyTitleLabel)
        self.replyTextLabel.font = Font.regular(14.0)
        self.replyTextLabel.numberOfLines = 0
        self.replyView.addSubview(self.replyTextLabel)
        self.addSubview(self.replyView)

        for button in [self.approveButton, self.dismissButton] {
            button.titleLabel?.font = Font.semibold(14.0)
            button.layer.cornerRadius = 9.0
            button.clipsToBounds = true
            self.addSubview(button)
        }
        self.approveButton.setTitle(ProfileCommentsStrings.approve, for: .normal)
        self.dismissButton.setTitle(ProfileCommentsStrings.dismiss, for: .normal)
        self.approveButton.addTarget(self, action: #selector(self.approvePressed), for: .touchUpInside)
        self.dismissButton.addTarget(self, action: #selector(self.dismissPressed), for: .touchUpInside)

        self.addSubview(self.divider)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(self.handleDoubleTap))
        doubleTap.numberOfTapsRequired = 2
        self.addGestureRecognizer(doubleTap)
        let tap = UITapGestureRecognizer(target: self, action: #selector(self.handleTap))
        tap.require(toFail: doubleTap)
        self.addGestureRecognizer(tap)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(self.handleLongPress(_:)))
        self.addGestureRecognizer(longPress)

        self.isAccessibilityElement = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func handleTap() {
        self.onTap?(self)
    }

    @objc private func handleDoubleTap() {
        self.onDoubleTap?(self)
    }

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
        if recognizer.state == .began {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            self.onTap?(self)
        }
    }

    @objc private func approvePressed() {
        self.onApprove?()
    }

    @objc private func dismissPressed() {
        self.onDismiss?()
    }

    @objc private func reactionPressed(_ sender: UIButton) {
        guard let emoji = sender.accessibilityIdentifier else {
            return
        }
        self.onReaction?(emoji)
    }

    /// Next update animates the pill for this reaction.
    func bumpReaction(_ emoji: String) {
        self.bumpEmoji = emoji
    }

    /// Lays out for the given width and returns the height.
    func update(item: Item, context: AccountContext, theme: PresentationTheme, width: CGFloat) -> CGFloat {
        self.item = item
        let comment = item.comment
        let dimmed = !(comment.status == .approved || item.isPendingSection)

        let leftInset: CGFloat = 16.0
        let contentLeft: CGFloat = 68.0
        let rightInset: CGFloat = 16.0
        let contentWidth = max(1.0, width - contentLeft - rightInset)
        var y: CGFloat = 12.0

        // Avatar
        self.avatarNode.frame = CGRect(x: leftInset, y: y, width: 40.0, height: 40.0)
        if let peer = item.authorPeer {
            self.avatarNode.setPeer(context: context, theme: theme, peer: peer, displayDimensions: CGSize(width: 40.0, height: 40.0))
        } else {
            let parts = item.authorName.split(separator: " ").prefix(2).map { String($0.prefix(1)).uppercased() }
            self.avatarNode.setCustomLetters(parts.isEmpty ? ["?"] : parts)
        }
        self.avatarNode.view.alpha = dimmed ? 0.55 : 1.0

        // Name, pin, date
        self.dateLabel.text = ProfileCommentsStore.formatAgo(comment.date)
        self.dateLabel.textColor = theme.list.itemSecondaryTextColor
        let dateSize = self.dateLabel.sizeThatFits(CGSize(width: contentWidth, height: 20.0))
        let showPin = comment.pinned && comment.status == .approved
        self.pinView.isHidden = !showPin
        self.pinView.tintColor = theme.list.itemAccentColor
        let pinWidth: CGFloat = showPin ? 18.0 : 0.0

        self.nameLabel.text = item.isMine && !item.isOwnerView ? ProfileCommentsStrings.you : item.authorName
        self.nameLabel.textColor = theme.list.itemPrimaryTextColor
        let nameMax = max(10.0, contentWidth - dateSize.width - pinWidth - 6.0)
        let nameSize = self.nameLabel.sizeThatFits(CGSize(width: nameMax, height: 22.0))
        self.nameLabel.frame = CGRect(x: contentLeft, y: y - 1.0, width: min(nameMax, nameSize.width), height: 20.0)
        self.dateLabel.frame = CGRect(x: width - rightInset - dateSize.width, y: y, width: dateSize.width, height: 18.0)
        self.pinView.frame = CGRect(x: self.dateLabel.frame.minX - pinWidth, y: y, width: 16.0, height: 18.0)
        y += 21.0

        // Tags
        let tagLine = ProfileCommentsStore.tagLine(comment)
        self.tagsLabel.isHidden = tagLine.isEmpty
        if !tagLine.isEmpty {
            self.tagsLabel.text = tagLine
            self.tagsLabel.textColor = theme.list.itemAccentColor
            self.tagsLabel.alpha = dimmed ? 0.55 : 1.0
            let size = self.tagsLabel.sizeThatFits(CGSize(width: contentWidth, height: .greatestFiniteMagnitude))
            self.tagsLabel.frame = CGRect(x: contentLeft, y: y + 1.0, width: contentWidth, height: ceil(size.height))
            y += ceil(size.height) + 2.0
        }

        // Status chip
        var status: (String, UIColor)?
        if comment.status == .pending && !item.isPendingSection {
            status = (ProfileCommentsStrings.awaiting, UIColor(rgb: 0xf08a24))
        } else if comment.status == .hidden {
            status = (ProfileCommentsStrings.hiddenLabel, theme.list.itemSecondaryTextColor)
        } else if item.isOwnerView && !comment.reports.isEmpty {
            status = (ProfileCommentsStrings.reports(comment.reports.count), theme.list.itemDestructiveColor)
        }
        self.statusLabel.isHidden = status == nil
        if let status {
            let (text, color) = status
            self.statusLabel.text = text
            self.statusLabel.textColor = color
            self.statusLabel.backgroundColor = color.withAlphaComponent(0.12)
            let size = self.statusLabel.sizeThatFits(CGSize(width: contentWidth, height: 20.0))
            self.statusLabel.frame = CGRect(x: contentLeft, y: y + 5.0, width: size.width, height: size.height)
            y += size.height + 5.0
        }

        // Text
        self.textLabel.text = comment.text
        self.textLabel.textColor = theme.list.itemPrimaryTextColor
        self.textLabel.alpha = dimmed ? 0.55 : 1.0
        let textSize = self.textLabel.sizeThatFits(CGSize(width: contentWidth, height: .greatestFiniteMagnitude))
        self.textLabel.frame = CGRect(x: contentLeft, y: y + 4.0, width: contentWidth, height: ceil(textSize.height))
        y += ceil(textSize.height) + 4.0

        // Reactions
        for button in self.reactionButtons {
            button.removeFromSuperview()
        }
        self.reactionButtons.removeAll()
        if comment.status == .approved {
            let sorted = comment.sortedReactions
            if !sorted.isEmpty {
                var x = contentLeft
                y += 8.0
                for emoji in sorted {
                    let mine = item.myReactions.contains(emoji)
                    let button = UIButton(type: .custom)
                    button.accessibilityIdentifier = emoji
                    button.accessibilityLabel = "\(emoji) \(comment.reactionCount(emoji))"
                    button.titleLabel?.font = Font.semibold(13.0)
                    button.setTitle("\(emoji) \(comment.reactionCount(emoji))", for: .normal)
                    button.setTitleColor(mine ? theme.list.itemCheckColors.foregroundColor : theme.list.itemAccentColor, for: .normal)
                    button.backgroundColor = mine ? theme.list.itemAccentColor : theme.list.itemAccentColor.withAlphaComponent(0.1)
                    button.layer.cornerRadius = 14.0
                    button.addTarget(self, action: #selector(self.reactionPressed(_:)), for: .touchUpInside)
                    let titleSize = ("\(emoji) \(comment.reactionCount(emoji))" as NSString).size(withAttributes: [.font: Font.semibold(13.0)])
                    let size = CGSize(width: ceil(titleSize.width) + 19.0, height: 28.0)
                    if x + size.width > width - rightInset && x > contentLeft {
                        x = contentLeft
                        y += 34.0
                    }
                    button.frame = CGRect(x: x, y: y, width: size.width, height: 28.0)
                    self.addSubview(button)
                    self.reactionButtons.append(button)
                    x += size.width + 6.0
                    if emoji == self.bumpEmoji {
                        button.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
                        UIView.animate(withDuration: 0.4, delay: 0.0, usingSpringWithDamping: 0.5, initialSpringVelocity: 0.0, options: [], animations: {
                            button.transform = .identity
                        })
                    }
                }
                y += 28.0
            }
        }
        self.bumpEmoji = nil

        // Owner reply
        self.replyView.isHidden = !comment.hasReply
        if comment.hasReply {
            y += 9.0
            let innerWidth = contentWidth - 22.0
            self.replyView.backgroundColor = theme.list.itemAccentColor.withAlphaComponent(0.08)
            self.replyBar.backgroundColor = theme.list.itemAccentColor
            self.replyAvatarNode.frame = CGRect(x: 12.0, y: 8.0, width: 18.0, height: 18.0)
            if let owner = item.ownerPeer {
                self.replyAvatarNode.setPeer(context: context, theme: theme, peer: owner, displayDimensions: CGSize(width: 18.0, height: 18.0))
            }
            self.replyTitleLabel.text = ProfileCommentsStrings.replyTitle(item.ownerName, ProfileCommentsStore.formatAgo(comment.replyDate ?? comment.date))
            self.replyTitleLabel.textColor = theme.list.itemAccentColor
            self.replyTitleLabel.frame = CGRect(x: 36.0, y: 8.0, width: innerWidth - 24.0, height: 18.0)
            self.replyTextLabel.text = comment.replyText
            self.replyTextLabel.textColor = theme.list.itemPrimaryTextColor
            let replySize = self.replyTextLabel.sizeThatFits(CGSize(width: innerWidth, height: .greatestFiniteMagnitude))
            self.replyTextLabel.frame = CGRect(x: 12.0, y: 29.0, width: innerWidth, height: ceil(replySize.height))
            let replyHeight = 29.0 + ceil(replySize.height) + 8.0
            self.replyView.frame = CGRect(x: contentLeft, y: y, width: contentWidth, height: replyHeight)
            self.replyBar.frame = CGRect(x: 0.0, y: 0.0, width: 3.0, height: replyHeight)
            y += replyHeight
        }

        // Moderation row (pending queue)
        let showModeration = item.isPendingSection
        self.approveButton.isHidden = !showModeration
        self.dismissButton.isHidden = !showModeration
        if showModeration {
            y += 10.0
            let buttonWidth = (contentWidth - 8.0) / 2.0
            self.approveButton.backgroundColor = theme.list.itemAccentColor
            self.approveButton.setTitleColor(theme.list.itemCheckColors.foregroundColor, for: .normal)
            self.dismissButton.backgroundColor = theme.list.itemDestructiveColor.withAlphaComponent(0.1)
            self.dismissButton.setTitleColor(theme.list.itemDestructiveColor, for: .normal)
            self.approveButton.frame = CGRect(x: contentLeft, y: y, width: buttonWidth, height: 36.0)
            self.dismissButton.frame = CGRect(x: contentLeft + buttonWidth + 8.0, y: y, width: buttonWidth, height: 36.0)
            y += 36.0
        }

        y += 12.0
        self.divider.isHidden = !item.showDivider
        self.divider.backgroundColor = theme.list.itemBlocksSeparatorColor
        self.divider.frame = CGRect(x: contentLeft, y: y - UIScreenPixel, width: width - contentLeft, height: UIScreenPixel)

        self.accessibilityLabel = "\(item.authorName), \(ProfileCommentsStore.formatAgo(comment.date)): \(comment.text)"
        return y
    }
}

/// UILabel with internal padding, used for status chips.
final class PaddedLabel: UILabel {
    var insets = UIEdgeInsets(top: 2.0, left: 7.0, bottom: 2.0, right: 7.0)

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: self.insets))
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        let base = super.sizeThatFits(CGSize(width: size.width - self.insets.left - self.insets.right, height: size.height))
        return CGSize(width: ceil(base.width) + self.insets.left + self.insets.right, height: ceil(base.height) + self.insets.top + self.insets.bottom)
    }
}
