import Foundation
import UIKit
import Display
import TelegramCore
import TelegramPresentationData
import AccountContext
import AvatarNode

/// Sheet for writing a comment (visitor) or replying to one (owner).
final class ProfileCommentComposeController: UIViewController, UITextViewDelegate {
    enum Mode {
        case comment(ownerFirstName: String, approval: Bool)
        case reply(authorFirstName: String, quote: String, existing: String?)
    }

    private let mode: Mode
    private let context: AccountContext
    private let theme: PresentationTheme
    private let selfPeer: EnginePeer?
    private let onMessage: (() -> Void)?
    /// Returns an error to show, or nil when the text was accepted.
    private let completion: (String, [String]) -> String?

    private var selectedTags: [String] = []

    private let scrollView = UIScrollView()
    private let avatarNode = AvatarNode(font: avatarPlaceholderFont(size: 16.0))
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let tagsTitleLabel = UILabel()
    private let tagsView = TagFlowView()
    private let fieldContainer = UIView()
    private let textView = UITextView()
    private let placeholderLabel = UILabel()
    private let counterLabel = UILabel()
    private let noteIcon = UIImageView()
    private let noteLabel = UILabel()
    private let messageButton = UIButton(type: .system)
    private let errorLabel = UILabel()
    private let postButton = UIButton(type: .system)

    private var isReply: Bool {
        if case .reply = self.mode {
            return true
        }
        return false
    }

    init(mode: Mode, context: AccountContext, theme: PresentationTheme, selfPeer: EnginePeer?, onMessage: (() -> Void)?, completion: @escaping (String, [String]) -> String?) {
        self.mode = mode
        self.context = context
        self.theme = theme
        self.selfPeer = selfPeer
        self.onMessage = onMessage
        self.completion = completion
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let theme = self.theme
        self.view.backgroundColor = theme.list.blocksBackgroundColor
        self.overrideUserInterfaceStyle = theme.overallDarkAppearance ? .dark : .light

        self.scrollView.alwaysBounceVertical = true
        self.scrollView.keyboardDismissMode = .interactive
        self.view.addSubview(self.scrollView)

        self.scrollView.addSubview(self.avatarNode.view)
        if let selfPeer = self.selfPeer {
            self.avatarNode.setPeer(context: self.context, theme: theme, peer: selfPeer, displayDimensions: CGSize(width: 40.0, height: 40.0))
        }

        self.titleLabel.font = Font.semibold(18.0)
        self.titleLabel.textColor = theme.list.itemPrimaryTextColor
        self.titleLabel.numberOfLines = 2
        self.scrollView.addSubview(self.titleLabel)

        self.subtitleLabel.font = Font.regular(13.0)
        self.subtitleLabel.textColor = theme.list.itemSecondaryTextColor
        self.subtitleLabel.numberOfLines = 2
        self.scrollView.addSubview(self.subtitleLabel)

        self.tagsTitleLabel.font = Font.semibold(14.0)
        self.tagsTitleLabel.textColor = theme.list.itemPrimaryTextColor
        self.scrollView.addSubview(self.tagsTitleLabel)
        self.scrollView.addSubview(self.tagsView)

        self.fieldContainer.backgroundColor = theme.list.itemBlocksBackgroundColor
        self.fieldContainer.layer.cornerRadius = 12.0
        self.scrollView.addSubview(self.fieldContainer)

        self.textView.font = Font.regular(16.0)
        self.textView.textColor = theme.list.itemPrimaryTextColor
        self.textView.backgroundColor = .clear
        self.textView.tintColor = theme.list.itemAccentColor
        self.textView.delegate = self
        self.textView.textContainerInset = UIEdgeInsets(top: 12.0, left: 10.0, bottom: 24.0, right: 10.0)
        self.textView.keyboardAppearance = theme.overallDarkAppearance ? .dark : .light
        self.fieldContainer.addSubview(self.textView)

        self.placeholderLabel.font = Font.regular(16.0)
        self.placeholderLabel.textColor = theme.list.itemPlaceholderTextColor
        self.placeholderLabel.numberOfLines = 0
        self.fieldContainer.addSubview(self.placeholderLabel)

        self.counterLabel.font = Font.regular(12.0)
        self.counterLabel.textAlignment = .right
        self.fieldContainer.addSubview(self.counterLabel)

        self.noteIcon.tintColor = theme.list.itemSecondaryTextColor
        self.noteIcon.contentMode = .center
        self.scrollView.addSubview(self.noteIcon)

        self.noteLabel.font = Font.regular(13.0)
        self.noteLabel.textColor = theme.list.itemSecondaryTextColor
        self.noteLabel.numberOfLines = 0
        self.scrollView.addSubview(self.noteLabel)

        self.messageButton.titleLabel?.font = Font.semibold(13.0)
        self.messageButton.setTitleColor(theme.list.itemAccentColor, for: .normal)
        self.messageButton.contentHorizontalAlignment = .left
        self.messageButton.addTarget(self, action: #selector(self.messagePressed), for: .touchUpInside)
        self.scrollView.addSubview(self.messageButton)

        self.errorLabel.font = Font.regular(13.0)
        self.errorLabel.textColor = theme.list.itemDestructiveColor
        self.errorLabel.numberOfLines = 0
        self.errorLabel.isHidden = true
        self.scrollView.addSubview(self.errorLabel)

        self.postButton.titleLabel?.font = Font.semibold(17.0)
        self.postButton.backgroundColor = theme.list.itemCheckColors.fillColor
        self.postButton.setTitleColor(theme.list.itemCheckColors.foregroundColor, for: .normal)
        self.postButton.layer.cornerRadius = 12.0
        self.postButton.addTarget(self, action: #selector(self.postPressed), for: .touchUpInside)
        self.scrollView.addSubview(self.postButton)

        switch self.mode {
        case let .comment(ownerFirstName, approval):
            self.titleLabel.text = ProfileCommentsStrings.sheetTitle(ownerFirstName)
            self.subtitleLabel.text = approval ? ProfileCommentsStrings.sheetSubApproval(ownerFirstName) : ProfileCommentsStrings.sheetSubPublic
            self.tagsTitleLabel.text = ProfileCommentsStrings.sheetTags(ownerFirstName)
            self.placeholderLabel.text = ProfileCommentsStrings.sheetHint(ownerFirstName)
            self.noteIcon.image = UIImage(systemName: "clock")
            self.noteLabel.text = ProfileCommentsStrings.sheetNote(ownerFirstName)
            self.messageButton.setTitle(ProfileCommentsStrings.message(ownerFirstName), for: .normal)
            self.postButton.setTitle(ProfileCommentsStrings.sheetPost, for: .normal)
            self.tagsView.configure(tags: ProfileCommentsStore.tags, theme: theme, onToggle: { [weak self] id in
                self?.toggleTag(id)
            })
        case let .reply(authorFirstName, quote, existing):
            self.titleLabel.text = existing == nil ? ProfileCommentsStrings.replyTo(authorFirstName) : ProfileCommentsStrings.editReplyTo(authorFirstName)
            let short = quote.count > 70 ? String(quote.prefix(70)) + "…" : quote
            self.subtitleLabel.text = "“\(short)”"
            self.tagsTitleLabel.isHidden = true
            self.tagsView.isHidden = true
            self.placeholderLabel.text = ProfileCommentsStrings.replyHint(authorFirstName)
            self.noteIcon.image = UIImage(systemName: "lock")
            self.noteLabel.text = ProfileCommentsStrings.replyNote(authorFirstName)
            self.messageButton.isHidden = true
            self.postButton.setTitle(ProfileCommentsStrings.replyPost, for: .normal)
            self.textView.text = existing
        }
        self.updateTextState()
        self.updateTags()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        self.textView.becomeFirstResponder()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let width = self.view.bounds.width
        let side: CGFloat = 20.0
        let inner = width - side * 2.0
        self.scrollView.frame = self.view.bounds

        var y: CGFloat = 28.0
        self.avatarNode.frame = CGRect(x: side, y: y, width: 40.0, height: 40.0)
        let titleWidth = inner - 52.0
        let titleSize = self.titleLabel.sizeThatFits(CGSize(width: titleWidth, height: .greatestFiniteMagnitude))
        let subtitleSize = self.subtitleLabel.sizeThatFits(CGSize(width: titleWidth, height: .greatestFiniteMagnitude))
        let headerHeight = max(40.0, ceil(titleSize.height) + 2.0 + ceil(subtitleSize.height))
        let titleY = y + (headerHeight - ceil(titleSize.height) - 2.0 - ceil(subtitleSize.height)) / 2.0
        self.titleLabel.frame = CGRect(x: side + 52.0, y: titleY, width: titleWidth, height: ceil(titleSize.height))
        self.subtitleLabel.frame = CGRect(x: side + 52.0, y: self.titleLabel.frame.maxY + 2.0, width: titleWidth, height: ceil(subtitleSize.height))
        y += headerHeight + 18.0

        if !self.tagsView.isHidden {
            self.tagsTitleLabel.frame = CGRect(x: side, y: y, width: inner, height: 18.0)
            y += 28.0
            let tagsHeight = self.tagsView.layout(width: inner)
            self.tagsView.frame = CGRect(x: side, y: y, width: inner, height: tagsHeight)
            y += tagsHeight + 18.0
        }

        let fieldHeight: CGFloat = 132.0
        self.fieldContainer.frame = CGRect(x: side - 4.0, y: y, width: inner + 8.0, height: fieldHeight)
        self.textView.frame = self.fieldContainer.bounds
        let placeholderSize = self.placeholderLabel.sizeThatFits(CGSize(width: inner - 24.0, height: .greatestFiniteMagnitude))
        self.placeholderLabel.frame = CGRect(x: 15.0, y: 12.0, width: inner - 24.0, height: ceil(placeholderSize.height))
        self.counterLabel.frame = CGRect(x: 0.0, y: fieldHeight - 22.0, width: self.fieldContainer.bounds.width - 12.0, height: 16.0)
        y += fieldHeight + 12.0

        self.noteIcon.frame = CGRect(x: side, y: y, width: 18.0, height: 18.0)
        let noteSize = self.noteLabel.sizeThatFits(CGSize(width: inner - 26.0, height: .greatestFiniteMagnitude))
        self.noteLabel.frame = CGRect(x: side + 26.0, y: y, width: inner - 26.0, height: ceil(noteSize.height))
        y += ceil(noteSize.height)
        if !self.messageButton.isHidden {
            self.messageButton.frame = CGRect(x: side + 26.0, y: y + 2.0, width: inner - 26.0, height: 24.0)
            y += 28.0
        }

        if !self.errorLabel.isHidden {
            let errorSize = self.errorLabel.sizeThatFits(CGSize(width: inner, height: .greatestFiniteMagnitude))
            self.errorLabel.frame = CGRect(x: side, y: y + 8.0, width: inner, height: ceil(errorSize.height))
            y += ceil(errorSize.height) + 8.0
        }

        y += 18.0
        self.postButton.frame = CGRect(x: side - 4.0, y: y, width: inner + 8.0, height: 50.0)
        y += 50.0 + 20.0
        self.scrollView.contentSize = CGSize(width: width, height: y)
    }

    private func toggleTag(_ id: String) {
        if let index = self.selectedTags.firstIndex(of: id) {
            self.selectedTags.remove(at: index)
        } else if self.selectedTags.count < ProfileCommentsStore.maxTags {
            self.selectedTags.append(id)
        } else {
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            return
        }
        UISelectionFeedbackGenerator().selectionChanged()
        self.updateTags()
    }

    private func updateTags() {
        self.tagsView.update(selected: self.selectedTags, limitReached: self.selectedTags.count >= ProfileCommentsStore.maxTags, theme: self.theme)
        self.view.setNeedsLayout()
    }

    private func updateTextState() {
        let text = self.textView.text ?? ""
        self.placeholderLabel.isHidden = !text.isEmpty
        let left = ProfileCommentsStore.maxLength - text.count
        self.counterLabel.text = "\(left)"
        self.counterLabel.textColor = left < 20 ? self.theme.list.itemDestructiveColor : self.theme.list.itemSecondaryTextColor
        let enabled = self.isReply || text.trimmingCharacters(in: .whitespacesAndNewlines).count >= ProfileCommentsStore.minLength
        self.postButton.isEnabled = enabled
        self.postButton.alpha = enabled ? 1.0 : 0.5
        if !self.errorLabel.isHidden {
            self.errorLabel.isHidden = true
            self.view.setNeedsLayout()
        }
    }

    func textViewDidChange(_ textView: UITextView) {
        self.updateTextState()
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        let current = (textView.text ?? "") as NSString
        let updated = current.replacingCharacters(in: range, with: text)
        return updated.count <= ProfileCommentsStore.maxLength
    }

    @objc private func messagePressed() {
        let onMessage = self.onMessage
        self.dismiss(animated: true, completion: {
            onMessage?()
        })
    }

    @objc private func postPressed() {
        let text = (self.textView.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if let error = self.completion(text, self.selectedTags) {
            self.errorLabel.text = error
            self.errorLabel.isHidden = false
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            self.view.setNeedsLayout()
            return
        }
        self.textView.resignFirstResponder()
        self.dismiss(animated: true)
    }

    /// Presents the sheet above whatever is on screen.
    static func present(_ controller: ProfileCommentComposeController, from view: UIView) {
        guard var top = view.window?.rootViewController else {
            return
        }
        while let presented = top.presentedViewController {
            top = presented
        }
        controller.modalPresentationStyle = .pageSheet
        if let sheet = controller.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
            if #available(iOS 16.0, *) {
                sheet.preferredCornerRadius = 16.0
            }
        }
        top.present(controller, animated: true)
    }
}

/// Chips that wrap onto new lines.
final class TagFlowView: UIView {
    private var buttons: [UIButton] = []
    private var onToggle: ((String) -> Void)?

    func configure(tags: [ProfileCommentsStore.Tag], theme: PresentationTheme, onToggle: @escaping (String) -> Void) {
        self.onToggle = onToggle
        for button in self.buttons {
            button.removeFromSuperview()
        }
        self.buttons = tags.map { tag in
            let button = UIButton(type: .custom)
            button.accessibilityIdentifier = tag.id
            button.setTitle("\(tag.emoji)  \(tag.title)", for: .normal)
            button.titleLabel?.font = Font.regular(14.0)
            button.layer.cornerRadius = 17.0
            button.addTarget(self, action: #selector(self.pressed(_:)), for: .touchUpInside)
            self.addSubview(button)
            return button
        }
    }

    func update(selected: [String], limitReached: Bool, theme: PresentationTheme) {
        for button in self.buttons {
            let on = selected.contains(button.accessibilityIdentifier ?? "")
            button.isSelected = on
            button.titleLabel?.font = on ? Font.semibold(14.0) : Font.regular(14.0)
            button.setTitleColor(on ? theme.list.itemAccentColor : theme.list.itemPrimaryTextColor, for: .normal)
            button.setTitleColor(on ? theme.list.itemAccentColor : theme.list.itemPrimaryTextColor, for: .selected)
            button.backgroundColor = on ? theme.list.itemAccentColor.withAlphaComponent(0.14) : theme.list.itemBlocksBackgroundColor
            button.alpha = !on && limitReached ? 0.45 : 1.0
            button.accessibilityTraits = on ? [.button, .selected] : [.button]
        }
        self.setNeedsLayout()
    }

    /// Lays the chips out for the given width and returns the height.
    func layout(width: CGFloat) -> CGFloat {
        var x: CGFloat = 0.0
        var y: CGFloat = 0.0
        let height: CGFloat = 34.0
        for button in self.buttons {
            let title = button.title(for: .normal) ?? ""
            let font = button.isSelected ? Font.semibold(14.0) : Font.regular(14.0)
            let textWidth = ceil((title as NSString).size(withAttributes: [.font: font]).width)
            let buttonWidth = min(width, textWidth + 24.0)
            if x > 0.0 && x + buttonWidth > width {
                x = 0.0
                y += height + 8.0
            }
            button.frame = CGRect(x: x, y: y, width: buttonWidth, height: height)
            x += buttonWidth + 8.0
        }
        return self.buttons.isEmpty ? 0.0 : y + height
    }

    @objc private func pressed(_ sender: UIButton) {
        if let id = sender.accessibilityIdentifier {
            self.onToggle?(id)
        }
    }
}
