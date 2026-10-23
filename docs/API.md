# Profile Comments: proposed API

The clients ship with a local store (`ProfileCommentsController` on Android, `ProfileCommentsStore` on iOS)
so the whole flow runs today. Each mutating method in those classes is named after the server method it maps to.
Swapping the store for these requests is the only server-side work needed.

## Types

```
profileComment#c1d0a7e1 flags:#
    pinned:flags.0?true
    pending:flags.1?true          // waiting for the owner's approval (visible to the owner and the author only)
    hidden:flags.2?true           // hidden from visitors by the owner
    reported:flags.3?true         // the current user reported it
    id:long
    from_id:Peer
    date:int
    text:string
    entities:flags.4?Vector<MessageEntity>
    tags:Vector<string>           // "recommend", "worked", "designer", "reliable", "creative", "mentor"
    reactions:flags.5?MessageReactions
    reply:flags.6?ProfileCommentReply
    reports_count:flags.7?int     // owner only
    = ProfileComment;

profileCommentReply#5b1f0e93 date:int text:string entities:flags.0?Vector<MessageEntity> = ProfileCommentReply;

profileCommentsSettings#0e7d3c55 flags:#
    approve:flags.0?true
    who:ProfileCommentsWho
    = ProfileCommentsSettings;

profileCommentsWhoEverybody#9a1b2c01 = ProfileCommentsWho;
profileCommentsWhoContacts#9a1b2c02 = ProfileCommentsWho;
profileCommentsWhoNobody#9a1b2c03 = ProfileCommentsWho;

profileComments.comments#8f0c4b21 flags:#
    count:int
    tag_counts:Vector<ProfileCommentTagCount>
    pending:flags.0?Vector<ProfileComment>    // owner only
    comments:Vector<ProfileComment>
    next_offset:flags.1?string
    can_comment:flags.2?true
    can_comment_after:flags.3?int             // unix time when the 24 h limit ends for the current user
    chats:Vector<Chat>
    users:Vector<User>
    = profileComments.Comments;

profileCommentTagCount#3d6e1f40 tag:string count:int = ProfileCommentTagCount;
```

`userFull` gets two optional fields so the profile can decide whether to show the tab without an extra request:

```
userFull ... profile_comments_count:flags2.N?int profile_comments_settings:flags2.N?ProfileCommentsSettings ...
```

## Methods

| Method | Who | Notes |
|---|---|---|
| `profileComments.getComments user_id:InputUser tag:flags.0?string offset:string limit:int = profileComments.Comments` | anyone | Pinned first, then newest first. |
| `profileComments.postComment user_id:InputUser text:string entities:flags.0?Vector<MessageEntity> tags:Vector<string> = ProfileComment` | visitor | Server enforces: 1 comment per author per profile per 24 h, max 300 chars, max 3 tags, the owner's `who` setting, blocks. Returns `pending` when approval is on. |
| `profileComments.deleteComment user_id:InputUser id:long = Bool` | owner, author | |
| `profileComments.setReply user_id:InputUser id:long text:string entities:flags.0?Vector<MessageEntity> = ProfileComment` | owner | Empty text removes the reply. One reply per comment. |
| `profileComments.setStatus user_id:InputUser id:long approved:flags.0?true hidden:flags.1?true = ProfileComment` | owner | Approve a pending comment, hide or show one. |
| `profileComments.togglePin user_id:InputUser id:long pinned:Bool = Bool` | owner | One pinned comment per profile. |
| `profileComments.sendReaction user_id:InputUser id:long reaction:Vector<Reaction> = ProfileComment` | anyone | Same reaction model as messages. |
| `profileComments.report user_id:InputUser id:long reason:ReportReason message:string = Bool` | visitor | |
| `account.setProfileCommentsSettings settings:ProfileCommentsSettings = Bool` | owner | Settings → Privacy and Security → Profile Comments. |

## Updates

```
updateProfileComments user_id:long count:int pending_count:int = Update;
```

Sent to the owner when a comment arrives or waits for approval, and to all of the owner's sessions after any
moderation action, so every device refreshes the tab badge and the list.

## Why it stays a wall, not a chat

* The 24-hour limit is per author per profile and is enforced by the server.
* Visitors can't reply to comments at all. Only the owner can, once per comment.
* Every menu has "Message <name>", which opens the normal private chat for real conversations.
