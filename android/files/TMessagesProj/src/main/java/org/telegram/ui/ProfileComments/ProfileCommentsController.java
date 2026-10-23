package org.telegram.ui.ProfileComments;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Profile Comments: a moderated comment wall on a user's profile. It is a wall, not a chat.
 *
 * Rules
 *  - one comment per person every 24 hours;
 *  - only the profile owner can reply (one reply per comment, shown under it);
 *  - visitors can't reply to each other, "Message" opens a private chat instead;
 *  - the owner chooses who can comment (Everybody / My Contacts / Nobody), can require approval,
 *    and can pin, hide or delete any comment. Visitors can report a comment.
 *
 * Storage
 *  The client keeps a per-account cache in SharedPreferences. Every mutating method below maps 1:1
 *  to a method of the proposed MTProto API (see docs/API.md: profileComments.*). Until the server
 *  side exists the cache is the source of truth, which is enough to run and demo the whole flow.
 */
public class ProfileCommentsController {

    /** Seeds sample comments on your own wall so the feature can be demoed on a fresh account. */
    public static boolean DEMO_DATA = true;

    public static final int WHO_EVERYBODY = 0;
    public static final int WHO_CONTACTS = 1;
    public static final int WHO_NOBODY = 2;

    public static final int STATUS_APPROVED = 0;
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_HIDDEN = 2;

    public static final long COOLDOWN_MS = 24L * 60 * 60 * 1000;
    public static final int MAX_LENGTH = 300;
    public static final int MIN_LENGTH = 2;
    public static final int MAX_TAGS = 3;

    public static final String[] REACTIONS = {"❤️", "👍", "🔥", "🥰", "👏", "😁", "🎉", "🤩", "😮", "🙏", "💯", "👌"};

    public static final class Tag {
        public final String id;
        public final String emoji;
        public final int titleRes;

        Tag(String id, String emoji, int titleRes) {
            this.id = id;
            this.emoji = emoji;
            this.titleRes = titleRes;
        }
    }

    public static final Tag[] TAGS = {
        new Tag("recommend", "👍", R.string.ProfileCommentsTagRecommend),
        new Tag("worked", "🤝", R.string.ProfileCommentsTagWorked),
        new Tag("designer", "🎨", R.string.ProfileCommentsTagDesigner),
        new Tag("reliable", "⚡", R.string.ProfileCommentsTagReliable),
        new Tag("creative", "✨", R.string.ProfileCommentsTagCreative),
        new Tag("mentor", "🧭", R.string.ProfileCommentsTagMentor),
    };

    public static Tag findTag(String id) {
        for (Tag t : TAGS) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    public static final class Comment {
        public long id;
        public long authorId;
        /** Used when the author isn't in the local user cache (and for demo data). */
        public String authorName;
        public String text;
        public long date;
        public final ArrayList<String> tags = new ArrayList<>();
        public int status;
        public boolean pinned;
        public String replyText;
        public long replyDate;
        /** emoji -> user ids, in insertion order. */
        public final LinkedHashMap<String, HashSet<Long>> reactions = new LinkedHashMap<>();
        public final HashSet<Long> reports = new HashSet<>();

        public boolean hasReply() {
            return !TextUtils.isEmpty(replyText);
        }

        public int reactionCount(String emoji) {
            HashSet<Long> set = reactions.get(emoji);
            return set == null ? 0 : set.size();
        }

        /** Reactions with at least one user, most popular first. */
        public ArrayList<String> sortedReactions() {
            ArrayList<String> out = new ArrayList<>();
            for (Map.Entry<String, HashSet<Long>> e : reactions.entrySet()) {
                if (!e.getValue().isEmpty()) out.add(e.getKey());
            }
            Collections.sort(out, (a, b) -> reactionCount(b) - reactionCount(a));
            return out;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("a", authorId);
            if (authorName != null) o.put("n", authorName);
            o.put("t", text);
            o.put("d", date);
            o.put("s", status);
            o.put("p", pinned);
            if (!tags.isEmpty()) o.put("g", new JSONArray(tags));
            if (replyText != null) {
                o.put("rt", replyText);
                o.put("rd", replyDate);
            }
            if (!reactions.isEmpty()) {
                JSONObject r = new JSONObject();
                for (Map.Entry<String, HashSet<Long>> e : reactions.entrySet()) {
                    JSONArray users = new JSONArray();
                    for (Long uid : e.getValue()) users.put(uid);
                    r.put(e.getKey(), users);
                }
                o.put("r", r);
            }
            if (!reports.isEmpty()) {
                JSONArray rep = new JSONArray();
                for (Long uid : reports) rep.put(uid);
                o.put("x", rep);
            }
            return o;
        }

        static Comment fromJson(JSONObject o) throws Exception {
            Comment c = new Comment();
            c.id = o.getLong("id");
            c.authorId = o.optLong("a");
            c.authorName = o.has("n") ? o.getString("n") : null;
            c.text = o.optString("t", "");
            c.date = o.optLong("d");
            c.status = o.optInt("s", STATUS_APPROVED);
            c.pinned = o.optBoolean("p", false);
            JSONArray g = o.optJSONArray("g");
            if (g != null) {
                for (int i = 0; i < g.length(); i++) c.tags.add(g.getString(i));
            }
            if (o.has("rt")) {
                c.replyText = o.getString("rt");
                c.replyDate = o.optLong("rd");
            }
            JSONObject r = o.optJSONObject("r");
            if (r != null) {
                Iterator<String> keys = r.keys();
                while (keys.hasNext()) {
                    String emoji = keys.next();
                    JSONArray users = r.getJSONArray(emoji);
                    HashSet<Long> set = new HashSet<>();
                    for (int i = 0; i < users.length(); i++) set.add(users.getLong(i));
                    c.reactions.put(emoji, set);
                }
            }
            JSONArray x = o.optJSONArray("x");
            if (x != null) {
                for (int i = 0; i < x.length(); i++) c.reports.add(x.getLong(i));
            }
            return c;
        }
    }

    public static final class Settings {
        public int who = WHO_EVERYBODY;
        public boolean approve;
    }

    /** Why the "Leave a comment" button is (un)available for the current user. */
    public static final class LeaveState {
        public final boolean allowed;
        public final CharSequence text;

        LeaveState(boolean allowed, CharSequence text) {
            this.allowed = allowed;
            this.text = text;
        }
    }

    public interface Listener {
        void onProfileCommentsUpdated(long ownerId);
    }

    private static final ProfileCommentsController[] instances = new ProfileCommentsController[UserConfig.MAX_ACCOUNT_COUNT];

    public static ProfileCommentsController getInstance(int account) {
        ProfileCommentsController c = instances[account];
        if (c == null) {
            synchronized (ProfileCommentsController.class) {
                c = instances[account];
                if (c == null) {
                    instances[account] = c = new ProfileCommentsController(account);
                }
            }
        }
        return c;
    }

    private final int currentAccount;
    private final SharedPreferences prefs;
    private final HashMap<Long, ArrayList<Comment>> walls = new HashMap<>();
    private final HashMap<Long, Settings> settings = new HashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();

    private ProfileCommentsController(int account) {
        currentAccount = account;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("profile_comments" + account, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- listeners

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged(long ownerId) {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onProfileCommentsUpdated(ownerId);
        }
    }

    // ---------------------------------------------------------------- identity

    public long selfId() {
        return UserConfig.getInstance(currentAccount).getClientUserId();
    }

    public boolean isOwner(long ownerId) {
        return ownerId == selfId();
    }

    /** Comments work on regular user profiles: not bots, not deleted accounts. */
    public boolean isSupported(long ownerId) {
        if (ownerId <= 0) return false;
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(ownerId);
        return user != null && !user.bot && !UserObject.isDeleted(user) && !UserObject.isReplyUser(user) && !UserObject.isService(ownerId);
    }

    /** Whether the Comments tab should appear on this profile. */
    public boolean isTabVisible(long ownerId) {
        if (!isSupported(ownerId)) return false;
        if (isOwner(ownerId)) return true;
        return approvedCount(ownerId) > 0 || getSettings(ownerId).who != WHO_NOBODY;
    }

    public String getOwnerFirstName(long ownerId) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(ownerId);
        String name = user != null ? UserObject.getFirstName(user) : null;
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public String getAuthorName(Comment c) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(c.authorId);
        if (user != null) return UserObject.getUserName(user);
        return c.authorName != null ? c.authorName : "";
    }

    public String getAuthorFirstName(Comment c) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(c.authorId);
        if (user != null) return UserObject.getFirstName(user);
        String n = c.authorName != null ? c.authorName.trim() : "";
        int space = n.indexOf(' ');
        return space > 0 ? n.substring(0, space) : n;
    }

    public boolean isMine(Comment c) {
        return c.authorId == selfId();
    }

    // ---------------------------------------------------------------- settings

    public Settings getSettings(long ownerId) {
        Settings s = settings.get(ownerId);
        if (s == null) {
            s = new Settings();
            // TODO(server): visitors get these values from userFull.profile_comments_settings.
            String raw = prefs.getString("settings_" + ownerId, null);
            if (raw != null) {
                try {
                    JSONObject o = new JSONObject(raw);
                    s.who = o.optInt("who", WHO_EVERYBODY);
                    s.approve = o.optBoolean("approve", false);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            settings.put(ownerId, s);
        }
        return s;
    }

    /** account.setProfileCommentsSettings */
    public void setMySettings(int who, boolean approve) {
        long me = selfId();
        Settings s = getSettings(me);
        s.who = who;
        s.approve = approve;
        try {
            JSONObject o = new JSONObject();
            o.put("who", who);
            o.put("approve", approve);
            prefs.edit().putString("settings_" + me, o.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        notifyChanged(me);
    }

    // ---------------------------------------------------------------- reading

    /** profileComments.getComments */
    public ArrayList<Comment> getAll(long ownerId) {
        ArrayList<Comment> list = walls.get(ownerId);
        if (list == null) {
            list = new ArrayList<>();
            String raw = prefs.getString("wall_" + ownerId, null);
            if (raw != null) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        list.add(Comment.fromJson(arr.getJSONObject(i)));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            } else if (DEMO_DATA && isOwner(ownerId)) {
                seedDemo(list);
                walls.put(ownerId, list);
                save(ownerId);
            }
            walls.put(ownerId, list);
        }
        return list;
    }

    /**
     * What the current user sees on the wall, newest first, pinned on top.
     * Owner: everything except the pending queue (shown separately). Visitor: approved comments
     * plus their own comment while it waits for approval.
     */
    public ArrayList<Comment> getVisible(long ownerId, String tagFilter) {
        final boolean owner = isOwner(ownerId);
        ArrayList<Comment> out = new ArrayList<>();
        for (Comment c : getAll(ownerId)) {
            boolean visible;
            if (owner) {
                visible = c.status != STATUS_PENDING;
            } else {
                visible = c.status == STATUS_APPROVED || c.status == STATUS_PENDING && isMine(c);
            }
            if (!visible) continue;
            if (tagFilter != null && !c.tags.contains(tagFilter)) continue;
            out.add(c);
        }
        Collections.sort(out, (a, b) -> {
            int pa = a.pinned && a.status == STATUS_APPROVED ? 1 : 0;
            int pb = b.pinned && b.status == STATUS_APPROVED ? 1 : 0;
            if (pa != pb) return pb - pa;
            return Long.compare(b.date, a.date);
        });
        return out;
    }

    public ArrayList<Comment> getPending(long ownerId) {
        ArrayList<Comment> out = new ArrayList<>();
        if (!isOwner(ownerId)) return out;
        for (Comment c : getAll(ownerId)) {
            if (c.status == STATUS_PENDING) out.add(c);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.date, a.date));
        return out;
    }

    public int approvedCount(long ownerId) {
        int n = 0;
        for (Comment c : getAll(ownerId)) {
            if (c.status == STATUS_APPROVED) n++;
        }
        return n;
    }

    /** Tag id -> number of approved comments with it, most used first. */
    public LinkedHashMap<String, Integer> getTagCounts(long ownerId) {
        HashMap<String, Integer> counts = new HashMap<>();
        for (Comment c : getAll(ownerId)) {
            if (c.status != STATUS_APPROVED) continue;
            for (String t : c.tags) {
                if (findTag(t) == null) continue;
                Integer v = counts.get(t);
                counts.put(t, v == null ? 1 : v + 1);
            }
        }
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    public Comment find(long ownerId, long commentId) {
        for (Comment c : getAll(ownerId)) {
            if (c.id == commentId) return c;
        }
        return null;
    }

    private Comment myRecent(long ownerId) {
        long since = System.currentTimeMillis() - COOLDOWN_MS;
        Comment best = null;
        for (Comment c : getAll(ownerId)) {
            if (isMine(c) && c.date > since && (best == null || c.date > best.date)) best = c;
        }
        return best;
    }

    public LeaveState getLeaveState(long ownerId) {
        if (isOwner(ownerId)) {
            return new LeaveState(false, null);
        }
        final String first = getOwnerFirstName(ownerId);
        final Settings s = getSettings(ownerId);
        if (s.who == WHO_NOBODY) {
            return new LeaveState(false, formatString(R.string.ProfileCommentsTurnedOff, first));
        }
        if (s.who == WHO_CONTACTS) {
            TLRPC.User owner = MessagesController.getInstance(currentAccount).getUser(ownerId);
            // The owner must have *us* in contacts; mutual_contact is the closest client-side signal.
            if (owner == null || !owner.mutual_contact) {
                return new LeaveState(false, formatString(R.string.ProfileCommentsContactsOnly, first));
            }
        }
        Comment recent = myRecent(ownerId);
        if (recent != null) {
            return new LeaveState(false, formatString(R.string.ProfileCommentsCooldown, formatWait(recent.date + COOLDOWN_MS - System.currentTimeMillis())));
        }
        return new LeaveState(true, getString(R.string.ProfileCommentsLeave));
    }

    // ---------------------------------------------------------------- writing

    /** profileComments.postComment */
    public Comment post(long ownerId, String text, ArrayList<String> tags) {
        if (!getLeaveState(ownerId).allowed) return null;
        text = text.trim();
        if (text.length() < MIN_LENGTH) return null;
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        Comment c = new Comment();
        c.id = System.currentTimeMillis();
        c.authorId = selfId();
        c.text = text;
        c.date = System.currentTimeMillis();
        for (String t : tags) {
            if (findTag(t) != null && c.tags.size() < MAX_TAGS && !c.tags.contains(t)) c.tags.add(t);
        }
        c.status = getSettings(ownerId).approve ? STATUS_PENDING : STATUS_APPROVED;
        getAll(ownerId).add(c);
        save(ownerId);
        notifyChanged(ownerId);
        return c;
    }

    /** profileComments.setReply (owner only; empty text removes the reply) */
    public void setReply(long ownerId, Comment c, String text) {
        if (!isOwner(ownerId)) return;
        text = text == null ? "" : text.trim();
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        c.replyText = text.isEmpty() ? null : text;
        c.replyDate = System.currentTimeMillis();
        save(ownerId);
        notifyChanged(ownerId);
    }

    /** profileComments.setStatus (owner only): approve, hide or show again */
    public void setStatus(long ownerId, Comment c, int status) {
        if (!isOwner(ownerId)) return;
        c.status = status;
        if (status != STATUS_APPROVED) c.pinned = false;
        save(ownerId);
        notifyChanged(ownerId);
    }

    /** profileComments.togglePin (owner only). One pinned comment at a time. */
    public boolean togglePin(long ownerId, Comment c) {
        if (!isOwner(ownerId) || c.status != STATUS_APPROVED) return false;
        boolean pin = !c.pinned;
        for (Comment other : getAll(ownerId)) other.pinned = false;
        c.pinned = pin;
        save(ownerId);
        notifyChanged(ownerId);
        return pin;
    }

    /** profileComments.deleteComment (owner: any comment, visitor: own comment) */
    public void delete(long ownerId, Comment c) {
        if (!isOwner(ownerId) && !isMine(c)) return;
        getAll(ownerId).remove(c);
        save(ownerId);
        notifyChanged(ownerId);
    }

    /** profileComments.report (visitors) */
    public void report(long ownerId, Comment c) {
        if (isOwner(ownerId) || isMine(c)) return;
        c.reports.add(selfId());
        save(ownerId);
        notifyChanged(ownerId);
    }

    public boolean isReportedByMe(Comment c) {
        return c.reports.contains(selfId());
    }

    public boolean hasMyReaction(Comment c, String emoji) {
        HashSet<Long> set = c.reactions.get(emoji);
        return set != null && set.contains(selfId());
    }

    /** profileComments.sendReaction. Returns true when the reaction was added. */
    public boolean toggleReaction(long ownerId, Comment c, String emoji) {
        if (c.status != STATUS_APPROVED) return false;
        HashSet<Long> set = c.reactions.get(emoji);
        if (set == null) {
            set = new HashSet<>();
            c.reactions.put(emoji, set);
        }
        boolean added;
        if (set.contains(selfId())) {
            set.remove(selfId());
            added = false;
        } else {
            set.add(selfId());
            added = true;
        }
        save(ownerId);
        notifyChanged(ownerId);
        return added;
    }

    private void save(long ownerId) {
        ArrayList<Comment> list = walls.get(ownerId);
        if (list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Comment c : list) arr.put(c.toJson());
            prefs.edit().putString("wall_" + ownerId, arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- formatting

    public static String formatAgo(long date) {
        long now = System.currentTimeMillis();
        long s = (now - date) / 1000;
        if (s < 60) return getString(R.string.ProfileCommentsJustNow);
        if (s < 3600) return formatString(R.string.ProfileCommentsMinAgo, (int) (s / 60));
        java.util.Calendar a = java.util.Calendar.getInstance();
        a.setTimeInMillis(date);
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(now);
        int days = (int) Math.round((startOfDay(b) - startOfDay(a)) / (double) (24 * 60 * 60 * 1000));
        if (days <= 0) return formatString(R.string.ProfileCommentsHoursAgo, (int) (s / 3600));
        if (days == 1) return getString(R.string.ProfileCommentsYesterday);
        if (days < 7) return formatString(R.string.ProfileCommentsDaysAgo, days);
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)) {
            return org.telegram.messenger.LocaleController.getInstance().getFormatterDayMonth().format(date);
        }
        return org.telegram.messenger.LocaleController.getInstance().getFormatterYear().format(date);
    }

    private static long startOfDay(java.util.Calendar c) {
        java.util.Calendar d = (java.util.Calendar) c.clone();
        d.set(java.util.Calendar.HOUR_OF_DAY, 0);
        d.set(java.util.Calendar.MINUTE, 0);
        d.set(java.util.Calendar.SECOND, 0);
        d.set(java.util.Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    public static String formatWait(long ms) {
        int mins = (int) Math.max(1, (ms + 59_999) / 60_000);
        int h = mins / 60, m = mins % 60;
        if (h > 0) {
            return m > 0 ? formatString(R.string.ProfileCommentsWaitHM, h, m) : formatString(R.string.ProfileCommentsWaitH, h);
        }
        return formatString(R.string.ProfileCommentsWaitM, m);
    }

    public static CharSequence tagLine(Comment c) {
        StringBuilder sb = new StringBuilder();
        for (String id : c.tags) {
            Tag t = findTag(id);
            if (t == null) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(t.emoji).append(' ').append(getString(t.titleRes));
        }
        return sb;
    }

    public static String whoLabel(int who) {
        switch (who) {
            case WHO_CONTACTS: return getString(R.string.ProfileCommentsWhoContacts);
            case WHO_NOBODY: return getString(R.string.ProfileCommentsWhoNobody);
            default: return getString(R.string.ProfileCommentsWhoEverybody);
        }
    }

    private static String getString(int res) {
        return org.telegram.messenger.LocaleController.getString(res);
    }

    private static String formatString(int res, Object... args) {
        return org.telegram.messenger.LocaleController.formatString(res, args);
    }

    // ---------------------------------------------------------------- demo

    private void seedDemo(ArrayList<Comment> list) {
        long now = System.currentTimeMillis();
        long day = COOLDOWN_MS;
        list.add(demo(1, "Sofia Martinez", "Hi from Barcelona! Saw the prototype you're building, it looks amazing 🎉",
            now - 4 * day, STATUS_APPROVED, new String[]{"creative"}, "🎉", 2));
        Comment noah = demo(2, "Noah Bennett", "Super reliable designer. Delivered the whole onboarding flow two days early.",
            now - day - 3_000_000L, STATUS_APPROVED, new String[]{"recommend", "reliable"}, "👍", 4);
        noah.pinned = true;
        noah.replyText = "Thanks Noah, that project was a joy 🙌";
        noah.replyDate = now - day;
        list.add(noah);
        list.add(demo(3, "Liam Brooks", "Thanks for the talk in the design group. The part about prototyping with real code changed how I work.",
            now - 3 * 3_600_000L, STATUS_APPROVED, new String[]{"mentor"}, "👍", 1));
        Comment emma = demo(4, "Emma Wilson", "The mockup you sent yesterday turned out so good. We shipped it to the whole team!",
            now - 20 * 60_000L, STATUS_APPROVED, new String[]{"worked", "recommend", "designer"}, "🔥", 3);
        HashSet<Long> heart = new HashSet<>();
        heart.add(-1L);
        emma.reactions.put("❤️", heart);
        list.add(emma);
        list.add(demo(5, "Olivia Hayes", "Can you share the Figma file? Would love to learn from it.",
            now - 6 * 60_000L, STATUS_PENDING, new String[0], null, 0));
        String me = getOwnerFirstName(selfId());
        list.add(demo(6, "Alex Morgan", (TextUtils.isEmpty(me) ? "You" : me) + " rebuilt our landing page in two days. Clean files and great communication! 🔥",
            now - 2 * 60_000L, STATUS_PENDING, new String[]{"recommend", "worked"}, null, 0));
    }

    private static Comment demo(long n, String name, String text, long date, int status, String[] tags, String emoji, int reactions) {
        Comment c = new Comment();
        c.id = n;
        // Negative ids never collide with real users and are treated as "not in cache".
        c.authorId = -1000 - n;
        c.authorName = name;
        c.text = text;
        c.date = date;
        c.status = status;
        Collections.addAll(c.tags, tags);
        if (emoji != null && reactions > 0) {
            HashSet<Long> set = new HashSet<>();
            for (int i = 0; i < reactions; i++) set.add(-10L - i);
            c.reactions.put(emoji, set);
        }
        return c;
    }
}
