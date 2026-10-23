load("@build_bazel_rules_swift//swift:swift.bzl", "swift_library")

swift_library(
    name = "ProfileCommentsUI",
    module_name = "ProfileCommentsUI",
    srcs = glob([
        "Sources/**/*.swift",
    ]),
    deps = [
        "//submodules/SSignalKit/SwiftSignalKit",
        "//submodules/AsyncDisplayKit",
        "//submodules/Display",
        "//submodules/TelegramCore",
        "//submodules/TelegramPresentationData",
        "//submodules/AccountContext",
        "//submodules/AvatarNode",
        "//submodules/ItemListUI",
        "//submodules/PresentationDataUtils",
        "//submodules/TextFormat",
        "//submodules/UndoUI",
        "//submodules/TelegramUI/Components/PeerInfo/PeerInfoPaneNode",
    ],
    visibility = [
        "//visibility:public",
    ],
)
