import Foundation
import UIKit
import Display
import SwiftSignalKit
import TelegramCore
import TelegramPresentationData
import ItemListUI
import TextFormat
import PresentationDataUtils
import AccountContext

/// Settings → Privacy and Security → Profile Comments.

private final class ProfileCommentsSettingsArguments {
    let setWho: (ProfileCommentsStore.Who) -> Void
    let setApprove: (Bool) -> Void

    init(setWho: @escaping (ProfileCommentsStore.Who) -> Void, setApprove: @escaping (Bool) -> Void) {
        self.setWho = setWho
        self.setApprove = setApprove
    }
}

private enum ProfileCommentsSettingsSection: Int32 {
    case who
    case moderation
    case how
}

private enum ProfileCommentsSettingsEntry: ItemListNodeEntry {
    case whoHeader(String)
    case who(Int32, ProfileCommentsStore.Who, String, Bool)
    case whoInfo(String)
    case moderationHeader(String)
    case approve(String, Bool)
    case approveInfo(String)
    case howHeader(String)
    case how(Int32, String, String)

    var section: ItemListSectionId {
        switch self {
        case .whoHeader, .who, .whoInfo:
            return ProfileCommentsSettingsSection.who.rawValue
        case .moderationHeader, .approve, .approveInfo:
            return ProfileCommentsSettingsSection.moderation.rawValue
        case .howHeader, .how:
            return ProfileCommentsSettingsSection.how.rawValue
        }
    }

    var stableId: Int32 {
        switch self {
        case .whoHeader:
            return 0
        case let .who(index, _, _, _):
            return 1 + index
        case .whoInfo:
            return 10
        case .moderationHeader:
            return 11
        case .approve:
            return 12
        case .approveInfo:
            return 13
        case .howHeader:
            return 14
        case let .how(index, _, _):
            return 15 + index
        }
    }

    static func <(lhs: ProfileCommentsSettingsEntry, rhs: ProfileCommentsSettingsEntry) -> Bool {
        return lhs.stableId < rhs.stableId
    }

    static func ==(lhs: ProfileCommentsSettingsEntry, rhs: ProfileCommentsSettingsEntry) -> Bool {
        switch lhs {
        case let .whoHeader(text):
            if case .whoHeader(text) = rhs { return true } else { return false }
        case let .who(index, value, text, checked):
            if case .who(index, value, text, checked) = rhs { return true } else { return false }
        case let .whoInfo(text):
            if case .whoInfo(text) = rhs { return true } else { return false }
        case let .moderationHeader(text):
            if case .moderationHeader(text) = rhs { return true } else { return false }
        case let .approve(text, value):
            if case .approve(text, value) = rhs { return true } else { return false }
        case let .approveInfo(text):
            if case .approveInfo(text) = rhs { return true } else { return false }
        case let .howHeader(text):
            if case .howHeader(text) = rhs { return true } else { return false }
        case let .how(index, title, subtitle):
            if case .how(index, title, subtitle) = rhs { return true } else { return false }
        }
    }

    func item(presentationData: ItemListPresentationData, arguments: Any) -> ListViewItem {
        let arguments = arguments as! ProfileCommentsSettingsArguments
        switch self {
        case let .whoHeader(text), let .moderationHeader(text), let .howHeader(text):
            return ItemListSectionHeaderItem(presentationData: presentationData, text: text, sectionId: self.section)
        case let .who(_, value, text, checked):
            return ItemListCheckboxItem(presentationData: presentationData, systemStyle: .glass, title: text, style: .left, checked: checked, zeroSeparatorInsets: false, sectionId: self.section, action: {
                arguments.setWho(value)
            })
        case let .whoInfo(text), let .approveInfo(text):
            return ItemListTextItem(presentationData: presentationData, text: .plain(text), sectionId: self.section)
        case let .approve(text, value):
            return ItemListSwitchItem(presentationData: presentationData, systemStyle: .glass, title: text, value: value, sectionId: self.section, style: .blocks, updated: { value in
                arguments.setApprove(value)
            })
        case let .how(_, title, subtitle):
            return ItemListTextWithLabelItem(presentationData: presentationData, label: title, text: subtitle, style: .blocks, enabledEntityTypes: [], multiline: true, sectionId: self.section, action: nil)
        }
    }
}

private func profileCommentsSettingsEntries(settings: ProfileCommentsStore.Settings) -> [ProfileCommentsSettingsEntry] {
    var entries: [ProfileCommentsSettingsEntry] = []
    entries.append(.whoHeader(ProfileCommentsStrings.whoHeader))
    entries.append(.who(0, .everybody, ProfileCommentsStrings.whoEverybody, settings.who == .everybody))
    entries.append(.who(1, .contacts, ProfileCommentsStrings.whoContacts, settings.who == .contacts))
    entries.append(.who(2, .nobody, ProfileCommentsStrings.whoNobody, settings.who == .nobody))
    switch settings.who {
    case .everybody:
        entries.append(.whoInfo(ProfileCommentsStrings.whoInfoEverybody))
    case .contacts:
        entries.append(.whoInfo(ProfileCommentsStrings.whoInfoContacts))
    case .nobody:
        entries.append(.whoInfo(ProfileCommentsStrings.whoInfoNobody))
    }
    entries.append(.moderationHeader(ProfileCommentsStrings.moderationHeader))
    entries.append(.approve(ProfileCommentsStrings.approveNew, settings.approve))
    entries.append(.approveInfo(ProfileCommentsStrings.approveInfo))
    entries.append(.howHeader(ProfileCommentsStrings.howHeader))
    entries.append(.how(0, ProfileCommentsStrings.how1, ProfileCommentsStrings.how1Sub))
    entries.append(.how(1, ProfileCommentsStrings.how2, ProfileCommentsStrings.how2Sub))
    entries.append(.how(2, ProfileCommentsStrings.how3, ProfileCommentsStrings.how3Sub))
    return entries
}

/// Value shown on the row in Privacy and Security.
public func profileCommentsPrivacyValue(context: AccountContext) -> String {
    let store = ProfileCommentsStore.shared(accountPeerId: context.account.peerId)
    let settings = store.settings(ownerId: context.account.peerId)
    return ProfileCommentsStrings.privacyValue(ProfileCommentsStore.whoTitle(settings.who), approval: settings.approve)
}

/// Emits whenever your own Profile Comments settings change.
public func profileCommentsPrivacyValueSignal(context: AccountContext) -> Signal<String, NoError> {
    let accountPeerId = context.account.peerId
    return Signal<String, NoError> { subscriber in
        let store = ProfileCommentsStore.shared(accountPeerId: accountPeerId)
        subscriber.putNext(profileCommentsPrivacyValue(context: context))
        return (store.updates
        |> filter { $0 == accountPeerId }).start(next: { _ in
            subscriber.putNext(profileCommentsPrivacyValue(context: context))
        })
    }
    |> runOn(Queue.mainQueue())
}

public func profileCommentsSettingsController(context: AccountContext) -> ViewController {
    let store = ProfileCommentsStore.shared(accountPeerId: context.account.peerId)
    let accountPeerId = context.account.peerId
    let settingsPromise = ValuePromise<ProfileCommentsStore.Settings>(store.settings(ownerId: accountPeerId), ignoreRepeated: true)

    let arguments = ProfileCommentsSettingsArguments(setWho: { who in
        store.updateMySettings { $0.who = who }
        settingsPromise.set(store.settings(ownerId: accountPeerId))
    }, setApprove: { value in
        store.updateMySettings { $0.approve = value }
        settingsPromise.set(store.settings(ownerId: accountPeerId))
    })

    let signal = combineLatest(queue: .mainQueue(), context.sharedContext.presentationData, settingsPromise.get())
    |> map { presentationData, settings -> (ItemListControllerState, (ItemListNodeState, Any)) in
        let controllerState = ItemListControllerState(presentationData: ItemListPresentationData(presentationData), title: .text(ProfileCommentsStrings.title), leftNavigationButton: nil, rightNavigationButton: nil, backNavigationButton: ItemListBackButton(title: presentationData.strings.Common_Back), animateChanges: true)
        let listState = ItemListNodeState(presentationData: ItemListPresentationData(presentationData), entries: profileCommentsSettingsEntries(settings: settings), style: .blocks, animateChanges: true)
        return (controllerState, (listState, arguments))
    }

    return ItemListController(context: context, state: signal)
}
