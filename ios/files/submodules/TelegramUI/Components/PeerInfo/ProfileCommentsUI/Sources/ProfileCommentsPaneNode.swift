import Foundation
import UIKit
import AsyncDisplayKit
import Display
import SwiftSignalKit
import TelegramCore
import TelegramPresentationData
import AccountContext
import PeerInfoPaneNode
import UndoUI

/// The "Comments" tab on a user profile, next to Posts and Gifts.
/// The owner and visitors get different layouts automatically.
public final class ProfileCommentsPaneNode: ASDisplayNode, PeerInfoPaneNode, UIScrollViewDelegate {
    private let context: AccountContext
    private let ownerId: EnginePeer.Id
    private let store: ProfileCommentsStore
    private let openPeer: (EnginePeer) -> Void

    public weak var parentController: ViewController?

    private let scrollView = UIScrollView()
    private let cardView = UIView()
    private let filtersScrollView = UIScrollView()
    private var filterButtons: [UIButton] = []
    private let pendingTitleLabel = UILabel()
    private let pendingHintLabel = UILabel()
    private let gapView = UIView()
    private let listTitleLabel = UILabel()
    private let listModeButton = UIButton(type: .system)
    private let emptyIcon = UIImageView()
    private let emptyTitleLabel = UILabel()
    private let emptyTextLabel = UILabel()
    private let footerLabel = UILabel()
    private var itemViews: [String: ProfileCommentItemView] = [:]

    private let leaveButtonBackground = UIView()
    private let leaveButton = UIButton(type: .system)

    private var tagFilter: String?
    private var peers: [EnginePeer.Id: EnginePeer] = [:]
    private var subscribedPeerIds: Set<EnginePeer.Id> = Set()
    private var peersDisposable: Disposable?
    private var updatesDisposable: Disposable?
    private var currentParams: (size: CGSize, topInset: CGFloat, sideInset: CGFloat, bottomInset: CGFloat, visibleHeight: CGFloat, isScrollingLockedAtTop: Bool, presentationData: PresentationData)?

    private let ready = Promise<Bool>()
    private var didSetReady = false
    public var isReady: Signal<Bool, NoError> {
        return self.ready.get()
    }

    private let statusPromise = Promise<PeerInfoStatusData?>(nil)
    public var status: Signal<PeerInfoStatusData?, NoError> {
        return self.statusPromise.get()
    }

    public var tabBarOffsetUpdated: ((ContainedViewLayoutTransition) -> Void)?
    public var tabBarOffset: CGFloat {
        return 0.0
    }

    public init(context: AccountContext, ownerId: EnginePeer.Id, openPeer: @escaping (EnginePeer) -> Void) {
        self.context = context
        self.ownerId = ownerId
        self.store = ProfileCommentsStore.shared(accountPeerId: context.account.peerId)
        self.openPeer = openPeer
        super.init()
    }

    deinit {
        self.peersDisposable?.dispose()
        self.updatesDisposable?.dispose()
    }

    override public func didLoad() {
        super.didLoad()

        self.scrollView.delegate = self
        self.scrollView.alwaysBounceVertical = true
        self.scrollView.showsVerticalScrollIndicator = false
        self.scrollView.contentInsetAdjustmentBehavior = .never
        self.view.addSubview(self.scrollView)

        self.cardView.layer.cornerRadius = 26.0
        self.cardView.clipsToBounds = true
        self.scrollView.addSubview(self.cardView)

        self.filtersScrollView.showsHorizontalScrollIndicator = false
        self.filtersScrollView.alwaysBounceHorizontal = true
        self.cardView.addSubview(self.filtersScrollView)

        self.pendingTitleLabel.font = Font.semibold(15.0)
        self.pendingHintLabel.font = Font.regular(13.0)
        self.cardView.addSubview(self.pendingTitleLabel)
        self.cardView.addSubview(self.pendingHintLabel)
        self.cardView.addSubview(self.gapView)

        self.listTitleLabel.font = Font.semibold(15.0)
        self.cardView.addSubview(self.listTitleLabel)
        self.listModeButton.titleLabel?.font = Font.regular(13.0)
        self.listModeButton.addTarget(self, action: #selector(self.modePressed), for: .touchUpInside)
        self.cardView.addSubview(self.listModeButton)

        self.emptyIcon.image = UIImage(systemName: "bubble.left.and.bubble.right", withConfiguration: UIImage.SymbolConfiguration(pointSize: 34.0, weight: .regular))
        self.emptyIcon.contentMode = .center
        self.cardView.addSubview(self.emptyIcon)
        self.emptyTitleLabel.font = Font.semibold(16.0)
        self.emptyTitleLabel.textAlignment = .center
        self.emptyTitleLabel.text = ProfileCommentsStrings.emptyTitle
        self.cardView.addSubview(self.emptyTitleLabel)
        self.emptyTextLabel.font = Font.regular(14.0)
        self.emptyTextLabel.textAlignment = .center
        self.emptyTextLabel.numberOfLines = 0
        self.cardView.addSubview(self.emptyTextLabel)

        self.footerLabel.font = Font.regular(13.0)
        self.footerLabel.textAlignment = .center
        self.footerLabel.numberOfLines = 0
        self.scrollView.addSubview(self.footerLabel)

        self.view.addSubview(self.leaveButtonBackground)
        self.leaveButton.titleLabel?.font = Font.semibold(17.0)
        self.leaveButton.layer.cornerRadius = 25.0
        self.leaveButton.clipsToBounds = true
        self.leaveButton.addTarget(self, action: #selector(self.leavePressed), for: .touchUpInside)
        self.view.addSubview(self.leaveButton)

        self.updatesDisposable = (self.store.updates
        |> filter { [weak self] id in
            return id == self?.ownerId
        }
        |> deliverOnMainQueue).startStrict(next: { [weak self] _ in
            self?.refresh(animated: true)
        })
        self.refresh(animated: false)
    }

    // MARK: - Data

    private var ownerPeer: EnginePeer? {
        return self.peers[self.ownerId]
    }

    private var ownerFirstName: String {
        if case let .user(user) = self.ownerPeer {
            return user.firstName ?? user.lastName ?? ""
        }
        return ""
    }

    private var ownerName: String {
        if case let .user(user) = self.ownerPeer {
            return [user.firstName, user.lastName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        }
        return ""
    }

    private var ownerIsMutualContact: Bool {
        if case let .user(user) = self.ownerPeer {
            return user.flags.contains(.mutualContact)
        }
        return false
    }

    private func authorName(_ comment: ProfileCommentsStore.Comment) -> String {
        if case let .user(user) = self.peers[EnginePeer.Id(comment.authorId)] {
            return [user.firstName, user.lastName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        }
        return comment.authorName ?? ""
    }

    private func authorFirstName(_ comment: ProfileCommentsStore.Comment) -> String {
        let name = self.authorName(comment)
        return name.split(separator: " ").first.map(String.init) ?? name
    }

    private func updatePeerSubscription() {
        var ids = Set<EnginePeer.Id>([self.ownerId, self.context.account.peerId])
        for comment in self.store.all(ownerId: self.ownerId) where comment.authorId > 0 {
            ids.insert(EnginePeer.Id(comment.authorId))
        }
        if ids == self.subscribedPeerIds {
            return
        }
        self.subscribedPeerIds = ids
        self.peersDisposable?.dispose()
        self.peersDisposable = (self.context.engine.data.subscribe(EngineDataMap(ids.map { TelegramEngine.EngineData.Item.Peer.Peer(id: $0) }))
        |> deliverOnMainQueue).startStrict(next: { [weak self] result in
            guard let self else {
                return
            }
            var peers: [EnginePeer.Id: EnginePeer] = [:]
            for (id, peer) in result {
                if let peer {
                    peers[id] = peer
                }
            }
            self.peers = peers
            self.refresh(animated: false)
        })
    }

    private func refresh(animated: Bool) {
        self.updatePeerSubscription()
        self.statusPromise.set(.single(PeerInfoStatusData(text: ProfileCommentsStrings.count(self.store.approvedCount(ownerId: self.ownerId)), isActivity: false, key: .comments)))
        if let params = self.currentParams {
            self.layout(params: params, transition: animated ? .animated(duration: 0.35, curve: .spring) : .immediate)
        }
    }

    // MARK: - PeerInfoPaneNode

    public func update(size: CGSize, topInset: CGFloat, sideInset: CGFloat, bottomInset: CGFloat, deviceMetrics: DeviceMetrics, visibleHeight: CGFloat, isScrollingLockedAtTop: Bool, expandProgress: CGFloat, navigationHeight: CGFloat, presentationData: PresentationData, synchronous: Bool, transition: ContainedViewLayoutTransition) {
        let params = (size: size, topInset: topInset, sideInset: sideInset, bottomInset: bottomInset, visibleHeight: visibleHeight, isScrollingLockedAtTop: isScrollingLockedAtTop, presentationData: presentationData)
        self.currentParams = params
        self.layout(params: params, transition: transition)
        if !self.didSetReady {
            self.didSetReady = true
            self.ready.set(.single(true))
        }
    }

    public func scrollToTop() -> Bool {
        if self.scrollView.contentOffset.y > 0.0 {
            self.scrollView.setContentOffset(CGPoint(), animated: true)
            return true
        }
        return false
    }

    public func transferVelocity(_ velocity: CGFloat) {
        guard velocity > 0.0 else {
            return
        }
        let maxOffset = max(0.0, self.scrollView.contentSize.height - self.scrollView.bounds.height)
        let target = min(maxOffset, self.scrollView.contentOffset.y + velocity * 0.25)
        self.scrollView.setContentOffset(CGPoint(x: 0.0, y: target), animated: true)
    }

    public func cancelPreviewGestures() {
    }

    public func findLoadedMessage(id: EngineMessage.Id) -> EngineMessage? {
        return nil
    }

    public func transitionNodeForGallery(messageId: EngineMessage.Id, media: EngineMedia) -> (ASDisplayNode, CGRect, () -> (UIView?, UIView?))? {
        return nil
    }

    public func addToTransitionSurface(view: UIView) {
    }

    public func updateHiddenMedia() {
    }

    public func updateSelectedMessages(animated: Bool) {
    }

    public func ensureMessageIsVisible(id: EngineMessage.Id) {
    }

    // MARK: - Layout

    private func layout(params: (size: CGSize, topInset: CGFloat, sideInset: CGFloat, bottomInset: CGFloat, visibleHeight: CGFloat, isScrollingLockedAtTop: Bool, presentationData: PresentationData), transition: ContainedViewLayoutTransition) {
        guard self.isNodeLoaded else {
            return
        }
        let theme = params.presentationData.theme
        let size = params.size
        let isOwner = self.store.isOwner(self.ownerId)
        let settings = self.store.settings(ownerId: self.ownerId)
        let approvedCount = self.store.approvedCount(ownerId: self.ownerId)
        let tagCounts = self.store.tagCounts(ownerId: self.ownerId)
        if let tagFilter = self.tagFilter, !tagCounts.contains(where: { $0.0 == tagFilter }) {
            self.tagFilter = nil
        }

        self.backgroundColor = theme.list.blocksBackgroundColor
        self.scrollView.frame = CGRect(origin: CGPoint(), size: size)
        self.scrollView.isScrollEnabled = !params.isScrollingLockedAtTop
        if params.isScrollingLockedAtTop && self.scrollView.contentOffset.y > 0.0 {
            self.scrollView.setContentOffset(CGPoint(), animated: false)
        }
        self.cardView.backgroundColor = theme.list.itemBlocksBackgroundColor

        let cardInset: CGFloat = 16.0 + params.sideInset
        let cardWidth = size.width - cardInset * 2.0
        var y: CGFloat = 0.0

        // Filters
        for button in self.filterButtons {
            button.removeFromSuperview()
        }
        self.filterButtons.removeAll()
        self.filtersScrollView.isHidden = approvedCount == 0
        if approvedCount > 0 {
            var chips: [(String?, String, Int)] = [(nil, ProfileCommentsStrings.filterAll, approvedCount)]
            for (id, count) in tagCounts {
                if let tag = ProfileCommentsStore.tag(id: id) {
                    chips.append((id, "\(tag.emoji) \(tag.title)", count))
                }
            }
            var x: CGFloat = 12.0
            for (id, title, count) in chips {
                let on = id == self.tagFilter
                let button = UIButton(type: .custom)
                button.accessibilityIdentifier = id ?? ""
                let attributed = NSMutableAttributedString(string: title + "  ", attributes: [.font: on ? Font.semibold(14.0) : Font.regular(14.0), .foregroundColor: on ? theme.list.itemAccentColor : theme.list.itemPrimaryTextColor])
                attributed.append(NSAttributedString(string: "\(count)", attributes: [.font: Font.semibold(14.0), .foregroundColor: on ? theme.list.itemAccentColor : theme.list.itemSecondaryTextColor]))
                button.setAttributedTitle(attributed, for: .normal)
                button.backgroundColor = on ? theme.list.itemAccentColor.withAlphaComponent(0.14) : theme.list.itemSecondaryTextColor.withAlphaComponent(0.1)
                button.layer.cornerRadius = 16.0
                button.addTarget(self, action: #selector(self.filterPressed(_:)), for: .touchUpInside)
                let width = ceil(attributed.size().width) + 24.0
                button.frame = CGRect(x: x, y: 0.0, width: width, height: 32.0)
                x += width + 8.0
                self.filtersScrollView.addSubview(button)
                self.filterButtons.append(button)
            }
            self.filtersScrollView.frame = CGRect(x: 0.0, y: 12.0, width: cardWidth, height: 32.0)
            self.filtersScrollView.contentSize = CGSize(width: x + 4.0, height: 32.0)
            y = 12.0 + 32.0 + 4.0
        }

        var usedKeys = Set<String>()
        func layoutItem(_ comment: ProfileCommentsStore.Comment, pending: Bool, divider: Bool) {
            let key = "\(pending ? "p" : "c")\(comment.id)"
            usedKeys.insert(key)
            let itemView: ProfileCommentItemView
            var isNew = false
            if let current = self.itemViews[key] {
                itemView = current
            } else {
                itemView = ProfileCommentItemView()
                isNew = true
                self.itemViews[key] = itemView
                self.cardView.addSubview(itemView)
            }
            let commentId = comment.id
            itemView.onTap = { [weak self] view in
                self?.openMenu(commentId: commentId, sourceView: view)
            }
            itemView.onDoubleTap = { [weak self] view in
                self?.react(commentId: commentId, emoji: ProfileCommentsStore.reactions[0], view: view)
            }
            itemView.onReaction = { [weak self, weak itemView] emoji in
                self?.react(commentId: commentId, emoji: emoji, view: itemView)
            }
            itemView.onApprove = { [weak self] in
                self?.approve(commentId: commentId)
            }
            itemView.onDismiss = { [weak self] in
                self?.dismiss(commentId: commentId)
            }
            let myReactions = Set(ProfileCommentsStore.reactions.filter { self.store.hasMyReaction(comment, emoji: $0) })
            let item = ProfileCommentItemView.Item(
                comment: comment,
                authorPeer: comment.authorId > 0 ? self.peers[EnginePeer.Id(comment.authorId)] : nil,
                authorName: self.authorName(comment),
                ownerPeer: self.ownerPeer,
                ownerName: self.ownerName,
                isOwnerView: isOwner,
                isMine: self.store.isMine(comment),
                isPendingSection: pending,
                myReactions: myReactions,
                showDivider: divider
            )
            let height = itemView.update(item: item, context: self.context, theme: theme, width: cardWidth)
            let frame = CGRect(x: 0.0, y: y, width: cardWidth, height: height)
            if isNew {
                itemView.frame = frame
                if case .animated = transition {
                    itemView.alpha = 0.0
                    transition.updateAlpha(layer: itemView.layer, alpha: 1.0)
                }
            } else {
                transition.updateFrame(view: itemView, frame: frame)
            }
            y += height
        }

        // Pending queue (owner)
        let pending = self.store.pending(ownerId: self.ownerId)
        self.pendingTitleLabel.isHidden = pending.isEmpty
        self.pendingHintLabel.isHidden = pending.isEmpty
        self.gapView.isHidden = pending.isEmpty
        if !pending.isEmpty {
            self.pendingTitleLabel.text = ProfileCommentsStrings.pendingHeader(pending.count)
            self.pendingTitleLabel.textColor = UIColor(rgb: 0xf08a24)
            self.pendingHintLabel.text = ProfileCommentsStrings.pendingHint
            self.pendingHintLabel.textColor = theme.list.itemSecondaryTextColor
            let hintSize = self.pendingHintLabel.sizeThatFits(CGSize(width: cardWidth, height: 20.0))
            transition.updateFrame(view: self.pendingHintLabel, frame: CGRect(x: cardWidth - 16.0 - hintSize.width, y: y + 14.0, width: hintSize.width, height: 20.0))
            transition.updateFrame(view: self.pendingTitleLabel, frame: CGRect(x: 16.0, y: y + 14.0, width: cardWidth - 32.0 - hintSize.width, height: 20.0))
            y += 40.0
            for (index, comment) in pending.enumerated() {
                layoutItem(comment, pending: true, divider: index != pending.count - 1)
            }
            self.gapView.backgroundColor = theme.list.blocksBackgroundColor
            transition.updateFrame(view: self.gapView, frame: CGRect(x: 0.0, y: y, width: cardWidth, height: 10.0))
            y += 10.0
        }

        // List header
        let mode: String
        switch settings.who {
        case .nobody:
            mode = ProfileCommentsStrings.modeOff
        case .contacts:
            mode = ProfileCommentsStrings.modeContacts
        case .everybody:
            mode = settings.approve ? ProfileCommentsStrings.modeApproval : ProfileCommentsStrings.modePublic
        }
        self.listTitleLabel.text = ProfileCommentsStrings.count(approvedCount)
        self.listTitleLabel.textColor = theme.list.itemPrimaryTextColor
        self.listModeButton.setTitle(isOwner ? "\(mode)  ·  \(ProfileCommentsStrings.settingsShort)" : mode, for: .normal)
        self.listModeButton.setTitleColor(isOwner ? theme.list.itemAccentColor : theme.list.itemSecondaryTextColor, for: .normal)
        self.listModeButton.isUserInteractionEnabled = isOwner
        let modeWidth = ceil((self.listModeButton.title(for: .normal)! as NSString).size(withAttributes: [.font: Font.regular(13.0)]).width) + 8.0
        transition.updateFrame(view: self.listModeButton, frame: CGRect(x: cardWidth - 12.0 - modeWidth, y: y + 10.0, width: modeWidth, height: 28.0))
        transition.updateFrame(view: self.listTitleLabel, frame: CGRect(x: 16.0, y: y + 14.0, width: cardWidth - 40.0 - modeWidth, height: 20.0))
        y += 40.0

        // Comments or empty state
        let visible = self.store.visible(ownerId: self.ownerId, tagFilter: self.tagFilter)
        let isEmpty = visible.isEmpty
        self.emptyIcon.isHidden = !isEmpty
        self.emptyTitleLabel.isHidden = !isEmpty
        self.emptyTextLabel.isHidden = !isEmpty
        if isEmpty {
            self.emptyIcon.tintColor = theme.list.itemSecondaryTextColor
            self.emptyTitleLabel.textColor = theme.list.itemPrimaryTextColor
            self.emptyTextLabel.textColor = theme.list.itemSecondaryTextColor
            if isOwner {
                self.emptyTextLabel.text = ProfileCommentsStrings.emptyOwner
            } else if settings.who == .nobody {
                self.emptyTextLabel.text = ProfileCommentsStrings.emptyOff(self.ownerFirstName)
            } else {
                self.emptyTextLabel.text = ProfileCommentsStrings.emptyVisitor(self.ownerFirstName)
            }
            self.emptyIcon.frame = CGRect(x: 0.0, y: y + 12.0, width: cardWidth, height: 44.0)
            self.emptyTitleLabel.frame = CGRect(x: 24.0, y: y + 62.0, width: cardWidth - 48.0, height: 22.0)
            let textSize = self.emptyTextLabel.sizeThatFits(CGSize(width: cardWidth - 48.0, height: .greatestFiniteMagnitude))
            self.emptyTextLabel.frame = CGRect(x: 24.0, y: y + 88.0, width: cardWidth - 48.0, height: ceil(textSize.height))
            y += 88.0 + ceil(textSize.height) + 20.0
        } else {
            for (index, comment) in visible.enumerated() {
                layoutItem(comment, pending: false, divider: index != visible.count - 1)
            }
        }

        for (key, view) in self.itemViews where !usedKeys.contains(key) {
            self.itemViews.removeValue(forKey: key)
            if case .animated = transition {
                transition.updateAlpha(layer: view.layer, alpha: 0.0, completion: { _ in
                    view.removeFromSuperview()
                })
            } else {
                view.removeFromSuperview()
            }
        }

        transition.updateFrame(view: self.cardView, frame: CGRect(x: cardInset, y: params.topInset, width: cardWidth, height: y))
        var contentHeight = params.topInset + y

        // Rules footer
        var prefix = ""
        if settings.who == .nobody {
            prefix = ProfileCommentsStrings.rulesOff(self.ownerFirstName)
        } else if settings.who == .contacts {
            prefix = ProfileCommentsStrings.rulesContacts(self.ownerFirstName)
        }
        self.footerLabel.text = prefix + ProfileCommentsStrings.rules(isOwner ? ProfileCommentsStrings.you.lowercased() : self.ownerFirstName)
        self.footerLabel.textColor = theme.list.freeTextColor
        let footerSize = self.footerLabel.sizeThatFits(CGSize(width: cardWidth - 16.0, height: .greatestFiniteMagnitude))
        transition.updateFrame(view: self.footerLabel, frame: CGRect(x: cardInset + 8.0, y: contentHeight + 12.0, width: cardWidth - 16.0, height: ceil(footerSize.height)))
        contentHeight += 12.0 + ceil(footerSize.height) + 16.0

        // Leave button (visitors only), pinned to the bottom of the visible part of the profile
        let leaveState = self.store.leaveState(ownerId: self.ownerId, ownerFirstName: self.ownerFirstName, ownerIsMutualContact: self.ownerIsMutualContact)
        let showLeave: Bool
        switch leaveState {
        case .owner:
            showLeave = false
        case .allowed:
            showLeave = true
            self.leaveButton.setTitle("✎  " + ProfileCommentsStrings.leave, for: .normal)
            self.leaveButton.alpha = 1.0
        case let .disabled(text):
            showLeave = true
            self.leaveButton.setTitle(text, for: .normal)
            self.leaveButton.alpha = 0.6
        }
        self.leaveButton.isHidden = !showLeave
        self.leaveButtonBackground.isHidden = !showLeave
        if showLeave {
            self.leaveButton.backgroundColor = theme.list.itemCheckColors.fillColor
            self.leaveButton.setTitleColor(theme.list.itemCheckColors.foregroundColor, for: .normal)
            let visibleBottom = min(size.height, params.visibleHeight)
            let buttonFrame = CGRect(x: cardInset, y: visibleBottom - params.bottomInset - 12.0 - 50.0, width: cardWidth, height: 50.0)
            transition.updateFrame(view: self.leaveButton, frame: buttonFrame)
            self.leaveButtonBackground.backgroundColor = theme.list.blocksBackgroundColor.withAlphaComponent(0.92)
            transition.updateFrame(view: self.leaveButtonBackground, frame: CGRect(x: 0.0, y: buttonFrame.minY - 12.0, width: size.width, height: size.height - buttonFrame.minY + 12.0))
            contentHeight += 50.0 + 24.0
        }

        self.scrollView.contentSize = CGSize(width: size.width, height: max(contentHeight + params.bottomInset, size.height))
    }

    // MARK: - Actions

    @objc private func filterPressed(_ sender: UIButton) {
        let id = sender.accessibilityIdentifier ?? ""
        let filter: String? = id.isEmpty ? nil : id
        if filter == self.tagFilter {
            return
        }
        self.tagFilter = filter
        UISelectionFeedbackGenerator().selectionChanged()
        self.refresh(animated: true)
    }

    @objc private func modePressed() {
        guard self.store.isOwner(self.ownerId) else {
            return
        }
        let controller = profileCommentsSettingsController(context: self.context)
        (self.parentController?.navigationController as? NavigationController)?.pushViewController(controller)
    }

    @objc private func leavePressed() {
        let state = self.store.leaveState(ownerId: self.ownerId, ownerFirstName: self.ownerFirstName, ownerIsMutualContact: self.ownerIsMutualContact)
        switch state {
        case .owner:
            return
        case let .disabled(text):
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            self.toast(text)
        case .allowed:
            let presentationData = self.context.sharedContext.currentPresentationData.with { $0 }
            let ownerFirstName = self.ownerFirstName
            let controller = ProfileCommentComposeController(
                mode: .comment(ownerFirstName: ownerFirstName, approval: self.store.settings(ownerId: self.ownerId).approve),
                context: self.context,
                theme: presentationData.theme,
                selfPeer: self.peers[self.context.account.peerId],
                onMessage: { [weak self] in
                    guard let self, let owner = self.ownerPeer else {
                        return
                    }
                    self.openPeer(owner)
                },
                completion: { [weak self] text, tags in
                    guard let self else {
                        return nil
                    }
                    guard let comment = self.store.post(ownerId: self.ownerId, text: text, tags: tags, ownerFirstName: ownerFirstName, ownerIsMutualContact: self.ownerIsMutualContact) else {
                        if case let .disabled(reason) = self.store.leaveState(ownerId: self.ownerId, ownerFirstName: ownerFirstName, ownerIsMutualContact: self.ownerIsMutualContact) {
                            return reason
                        }
                        return nil
                    }
                    self.tagFilter = nil
                    let _ = self.scrollToTop()
                    self.toast(comment.status == .pending ? ProfileCommentsStrings.toastPending(ownerFirstName) : ProfileCommentsStrings.toastPosted)
                    return nil
                }
            )
            ProfileCommentComposeController.present(controller, from: self.view)
        }
    }

    private func comment(_ id: Int64) -> ProfileCommentsStore.Comment? {
        return self.store.all(ownerId: self.ownerId).first(where: { $0.id == id })
    }

    private func react(commentId: Int64, emoji: String, view: ProfileCommentItemView?) {
        guard let comment = self.comment(commentId), comment.status == .approved else {
            return
        }
        view?.bumpReaction(emoji)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        self.store.toggleReaction(ownerId: self.ownerId, commentId: commentId, emoji: emoji)
    }

    private func approve(commentId: Int64) {
        self.store.setStatus(ownerId: self.ownerId, commentId: commentId, status: .approved)
        self.toast(ProfileCommentsStrings.toastApproved)
    }

    private func dismiss(commentId: Int64) {
        self.store.delete(ownerId: self.ownerId, commentId: commentId)
        self.toast(ProfileCommentsStrings.toastDismissed)
    }

    private func openMenu(commentId: Int64, sourceView: ProfileCommentItemView) {
        guard let comment = self.comment(commentId) else {
            return
        }
        let presentationData = self.context.sharedContext.currentPresentationData.with { $0 }
        let isOwner = self.store.isOwner(self.ownerId)
        let isMine = self.store.isMine(comment)
        let authorFirst = self.authorFirstName(comment)
        let actionSheet = ActionSheetController(presentationData: presentationData)
        let dismissAction: () -> Void = { [weak actionSheet] in
            actionSheet?.dismissAnimated()
        }
        var items: [ActionSheetItem] = []

        if comment.status == .approved {
            items.append(ActionSheetButtonItem(title: ProfileCommentsStrings.react, color: .accent, action: { [weak self, weak sourceView] in
                dismissAction()
                guard let self, let sourceView else {
                    return
                }
                self.showReactionPicker(commentId: commentId, sourceView: sourceView)
            }))
        }
        if isOwner {
            if comment.status == .pending {
                items.append(ActionSheetButtonItem(title: ProfileCommentsStrings.approve, color: .accent, action: { [weak self] in
                    dismissAction()
                    self?.approve(commentId: commentId)
                }))
            } else {
                items.append(ActionSheetButtonItem(title: comment.hasReply ? ProfileCommentsStrings.editReply : ProfileCommentsStrings.reply, color: .accent, action: { [weak self] in
                    dismissAction()
                    self?.openReply(commentId: commentId)
                }))
                if comment.hasReply {
                    items.append(ActionSheetButtonItem(title: ProfileCommentsStrings.deleteReply, color: .accent, action: { [weak self] in
                        dismissAction()
                        guard let self else {
                            return
                        }
                        self.store.setReply(ownerId: self.ownerId, commentId: commentId, text: nil)
                        self.toast(ProfileCommentsStrings.toastReplyDeleted)
                    }))
                }
                if comment.status == .approved {
                    items.append(ActionSheetButtonItem(title: comment.pinned ? ProfileCommentsStrings.unpin : ProfileCommentsStrings.pin, color: .accent, action: { [weak self] in
                        dismissAction()
                        guard let self else {
                            return
                        }
                        let pinned = self.store.togglePin(ownerId: self.ownerId, commentId: commentId)
                        self.toast(pinned ? ProfileCommentsStrings.toastPinned : ProfileCommentsStrings.toastUnpinned)
                    }))
                }
                let hidden = comment.status == .hidden
                items.append(ActionSheetButtonItem(title: hidden ? ProfileCommentsStrings.show : ProfileCommentsStrings.hide, color: .accent, action: { [weak self] in
                    dismissAction()
                    guard let self else {
                        return
                    }
                    self.store.setStatus(ownerId: self.ownerId, commentId: commentId, status: hidden ? .approved : .hidden)
                    self.toast(hidden ? ProfileCommentsStrings.toastShown : ProfileCommentsStrings.toastHidden)
                }))
            }
        }
        if !isMine {
            items.append(ActionSheetButtonItem(title: ProfileCommentsStrings.message(authorFirst), color: .accent, action: { [weak self] in
                dismissAction()
                guard let self else {
                    return
                }
                if comment.authorId > 0, let peer = self.peers[EnginePeer.Id(comment.authorId)] {
                    self.openPeer(peer)
                } else {
                    self.toast(ProfileCommentsStrings.sampleUser)
                }
            }))
        }
        items.append(ActionSheetButtonItem(title: ProfileCommentsStrings.copy, color: .accent, action: { [weak self] in
            dismissAction()
            UIPasteboard.general.string = comment.text
            self?.toast(ProfileCommentsStrings.toastCopied)
        }))
        if !isOwner && !isMine {
            let reported = self.store.isReportedByMe(comment)
            items.append(ActionSheetButtonItem(title: reported ? ProfileCommentsStrings.reported : ProfileCommentsStrings.report, color: reported ? .disabled : .destructive, enabled: !reported, action: { [weak self] in
                dismissAction()
                guard let self else {
                    return
                }
                self.store.report(ownerId: self.ownerId, commentId: commentId)
                self.toast(ProfileCommentsStrings.toastReported(self.ownerFirstName))
            }))
        }
        if isOwner || isMine {
            let title: String
            if isOwner && !isMine {
                title = comment.status == .pending ? ProfileCommentsStrings.decline : ProfileCommentsStrings.delete
            } else {
                title = ProfileCommentsStrings.deleteMine
            }
            items.append(ActionSheetButtonItem(title: title, color: .destructive, action: { [weak self] in
                dismissAction()
                guard let self else {
                    return
                }
                self.store.delete(ownerId: self.ownerId, commentId: commentId)
                self.toast(ProfileCommentsStrings.toastDeleted)
            }))
        }

        actionSheet.setItemGroups([
            ActionSheetItemGroup(items: items),
            ActionSheetItemGroup(items: [ActionSheetButtonItem(title: ProfileCommentsStrings.cancel, color: .accent, font: .bold, action: dismissAction)])
        ])
        self.parentController?.present(actionSheet, in: .window(.root))
    }

    private func showReactionPicker(commentId: Int64, sourceView: ProfileCommentItemView) {
        guard let theme = self.currentParams?.presentationData.theme else {
            return
        }
        let overlay = ReactionStripOverlay(theme: theme, selected: Set(ProfileCommentsStore.reactions.filter { emoji in
            guard let comment = self.comment(commentId) else {
                return false
            }
            return self.store.hasMyReaction(comment, emoji: emoji)
        }), onSelect: { [weak self, weak sourceView] emoji in
            self?.react(commentId: commentId, emoji: emoji, view: sourceView)
        })
        overlay.frame = self.view.bounds
        self.view.addSubview(overlay)
        let anchor = sourceView.convert(sourceView.bounds, to: self.view)
        overlay.show(above: anchor)
    }

    private func openReply(commentId: Int64) {
        guard let comment = self.comment(commentId) else {
            return
        }
        let presentationData = self.context.sharedContext.currentPresentationData.with { $0 }
        let controller = ProfileCommentComposeController(
            mode: .reply(authorFirstName: self.authorFirstName(comment), quote: comment.text, existing: comment.replyText),
            context: self.context,
            theme: presentationData.theme,
            selfPeer: self.peers[self.context.account.peerId],
            onMessage: nil,
            completion: { [weak self] text, _ in
                guard let self else {
                    return nil
                }
                self.store.setReply(ownerId: self.ownerId, commentId: commentId, text: text)
                self.toast(text.isEmpty ? ProfileCommentsStrings.toastReplyDeleted : ProfileCommentsStrings.toastReply)
                return nil
            }
        )
        ProfileCommentComposeController.present(controller, from: self.view)
    }

    private func toast(_ text: String) {
        let presentationData = self.context.sharedContext.currentPresentationData.with { $0 }
        let controller = UndoOverlayController(presentationData: presentationData, content: .info(title: nil, text: text, timeout: nil, customUndoText: nil), elevatedLayout: false, action: { _ in
            return false
        })
        self.parentController?.present(controller, in: .current)
    }
}

/// Floating row of quick reactions shown above a comment.
private final class ReactionStripOverlay: UIView {
    private let container = UIView()
    private let scrollView = UIScrollView()
    private let onSelect: (String) -> Void

    init(theme: PresentationTheme, selected: Set<String>, onSelect: @escaping (String) -> Void) {
        self.onSelect = onSelect
        super.init(frame: CGRect())
        self.backgroundColor = UIColor(white: 0.0, alpha: 0.12)

        self.container.backgroundColor = theme.list.itemBlocksBackgroundColor
        self.container.layer.cornerRadius = 26.0
        self.container.layer.shadowColor = UIColor.black.cgColor
        self.container.layer.shadowOpacity = 0.18
        self.container.layer.shadowRadius = 16.0
        self.container.layer.shadowOffset = CGSize(width: 0.0, height: 4.0)
        self.addSubview(self.container)

        self.scrollView.showsHorizontalScrollIndicator = false
        self.scrollView.layer.cornerRadius = 26.0
        self.scrollView.clipsToBounds = true
        self.container.addSubview(self.scrollView)

        var x: CGFloat = 6.0
        for emoji in ProfileCommentsStore.reactions {
            let button = UIButton(type: .custom)
            button.setTitle(emoji, for: .normal)
            button.titleLabel?.font = UIFont.systemFont(ofSize: 26.0)
            button.accessibilityLabel = emoji
            if selected.contains(emoji) {
                button.backgroundColor = theme.list.itemAccentColor.withAlphaComponent(0.15)
                button.layer.cornerRadius = 20.0
            }
            button.frame = CGRect(x: x, y: 6.0, width: 40.0, height: 40.0)
            button.addTarget(self, action: #selector(self.pressed(_:)), for: .touchUpInside)
            self.scrollView.addSubview(button)
            x += 42.0
        }
        self.scrollView.contentSize = CGSize(width: x + 4.0, height: 52.0)

        let tap = UITapGestureRecognizer(target: self, action: #selector(self.backgroundTapped))
        tap.cancelsTouchesInView = false
        self.addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func show(above anchor: CGRect) {
        let width = min(self.bounds.width - 32.0, self.scrollView.contentSize.width)
        let y = max(12.0, anchor.minY - 52.0 - 8.0)
        self.container.frame = CGRect(x: (self.bounds.width - width) / 2.0, y: y, width: width, height: 52.0)
        self.scrollView.frame = self.container.bounds
        self.alpha = 0.0
        self.container.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
        UIView.animate(withDuration: 0.3, delay: 0.0, usingSpringWithDamping: 0.7, initialSpringVelocity: 0.0, options: [], animations: {
            self.alpha = 1.0
            self.container.transform = .identity
        })
    }

    private func close() {
        UIView.animate(withDuration: 0.18, animations: {
            self.alpha = 0.0
        }, completion: { _ in
            self.removeFromSuperview()
        })
    }

    @objc private func pressed(_ sender: UIButton) {
        if let emoji = sender.title(for: .normal) {
            self.onSelect(emoji)
        }
        self.close()
    }

    @objc private func backgroundTapped(_ recognizer: UITapGestureRecognizer) {
        if !self.container.frame.contains(recognizer.location(in: self)) {
            self.close()
        }
    }
}
