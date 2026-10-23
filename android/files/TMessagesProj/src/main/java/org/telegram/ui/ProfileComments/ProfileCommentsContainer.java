package org.telegram.ui.ProfileComments;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Content of the "Comments" tab on a profile (lives inside SharedMediaLayout, next to Posts and Gifts).
 * The owner and visitors get different layouts automatically.
 */
@SuppressLint("ViewConstructor")
public class ProfileCommentsContainer extends FrameLayout implements ProfileCommentsController.Listener, ProfileCommentCell.Delegate {

    private static final int TYPE_FILTERS = 0;
    private static final int TYPE_PENDING_HEADER = 1;
    private static final int TYPE_COMMENT = 2;
    private static final int TYPE_LIST_HEADER = 3;
    private static final int TYPE_EMPTY = 4;
    private static final int TYPE_FOOTER = 5;
    private static final int TYPE_GAP = 6;
    private static final int TYPE_BOTTOM_SPACE = 7;

    private final BaseFragment fragment;
    private final int currentAccount;
    private final long ownerId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ProfileCommentsController controller;

    private final RecyclerListView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter;
    private final FrameLayout buttonContainer;
    private final ButtonWithCounterView button;

    private final ArrayList<Item> items = new ArrayList<>();
    private String tagFilter;
    private int externalPaddingTop;
    private int visibleHeight = AndroidUtilities.displaySize.y;
    private String bumpKey;

    public ProfileCommentsContainer(BaseFragment fragment, Context context, int currentAccount, long ownerId, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = currentAccount;
        this.ownerId = ownerId;
        this.resourcesProvider = resourcesProvider;
        this.controller = ProfileCommentsController.getInstance(currentAccount);

        listView = new RecyclerListView(context, resourcesProvider);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new Adapter());
        listView.setClipToPadding(false);
        listView.setSelectorType(9);
        listView.setSelectorDrawableColor(0);
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDurations(350);
        listView.setItemAnimator(itemAnimator);
        listView.setPadding(0, externalPaddingTop, 0, 0);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // "Leave a comment" floats at the bottom of the visible part of the profile (visitors only).
        buttonContainer = new FrameLayout(context);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{
            Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
            Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
            Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), 0f)
        });
        buttonContainer.setBackground(fade);
        button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setOnClickListener(v -> onLeaveClick());
        buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 0, 16, 10));
        FrameLayout.LayoutParams lp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 76, Gravity.BOTTOM);
        lp.bottomMargin = AndroidUtilities.navigationBarHeight;
        addView(buttonContainer, lp);

        update(false);
    }

    // ------------------------------------------------------------------ host API (SharedMediaLayout)

    public RecyclerListView getCurrentListView() {
        return listView;
    }

    public void setPaddingTop(int paddingTop) {
        if (externalPaddingTop == paddingTop) return;
        final int old = listView.getPaddingTop();
        externalPaddingTop = paddingTop;
        listView.setPadding(0, paddingTop, 0, 0);
        final int scroll = old - paddingTop;
        AndroidUtilities.doOnLayout(listView, () -> listView.scrollBy(0, scroll));
    }

    public void setVisibleHeight(int height) {
        visibleHeight = height;
        updateButtonPosition();
    }

    public boolean canScroll(boolean forward) {
        return true;
    }

    public int getApprovedCount() {
        return controller.approvedCount(ownerId);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        controller.addListener(this);
        update(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        controller.removeListener(this);
    }

    @Override
    public void onProfileCommentsUpdated(long ownerId) {
        if (ownerId == this.ownerId) update(true);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateButtonPosition();
    }

    // ------------------------------------------------------------------ items

    private static final class Item {
        final int type;
        final long stableId;
        final int contentHash;
        final ProfileCommentsController.Comment comment;
        final boolean pending;
        final boolean divider;

        Item(int type, long stableId, int contentHash, ProfileCommentsController.Comment comment, boolean pending, boolean divider) {
            this.type = type;
            this.stableId = stableId;
            this.contentHash = contentHash;
            this.comment = comment;
            this.pending = pending;
            this.divider = divider;
        }
    }

    private static int hashOf(ProfileCommentsController.Comment c) {
        int reactions = 0;
        for (Map.Entry<String, java.util.HashSet<Long>> e : c.reactions.entrySet()) {
            reactions = reactions * 31 + e.getKey().hashCode() * 7 + e.getValue().hashCode();
        }
        return Objects.hash(c.text, c.status, c.pinned, c.replyText, c.tags, reactions, c.reports.size(), ProfileCommentsController.formatAgo(c.date));
    }

    public void update(boolean animated) {
        final boolean owner = controller.isOwner(ownerId);
        final ProfileCommentsController.Settings settings = controller.getSettings(ownerId);
        final int approved = controller.approvedCount(ownerId);
        final Map<String, Integer> tagCounts = controller.getTagCounts(ownerId);
        if (tagFilter != null && !tagCounts.containsKey(tagFilter)) tagFilter = null;

        final ArrayList<Item> old = new ArrayList<>(items);
        items.clear();

        if (approved > 0) {
            items.add(new Item(TYPE_FILTERS, 1, Objects.hash(tagCounts, tagFilter, approved), null, false, false));
        }

        ArrayList<ProfileCommentsController.Comment> pending = controller.getPending(ownerId);
        if (!pending.isEmpty()) {
            items.add(new Item(TYPE_PENDING_HEADER, 2, pending.size(), null, false, false));
            for (int i = 0; i < pending.size(); i++) {
                ProfileCommentsController.Comment c = pending.get(i);
                items.add(new Item(TYPE_COMMENT, 1_000_000_000L + c.id, hashOf(c), c, true, i < pending.size() - 1));
            }
            items.add(new Item(TYPE_GAP, 3, 0, null, false, false));
        }

        items.add(new Item(TYPE_LIST_HEADER, 4, Objects.hash(approved, settings.who, settings.approve, owner), null, false, false));
        ArrayList<ProfileCommentsController.Comment> visible = controller.getVisible(ownerId, tagFilter);
        if (visible.isEmpty()) {
            items.add(new Item(TYPE_EMPTY, 5, settings.who, null, false, false));
        } else {
            for (int i = 0; i < visible.size(); i++) {
                ProfileCommentsController.Comment c = visible.get(i);
                items.add(new Item(TYPE_COMMENT, 2_000_000_000L + c.id, hashOf(c), c, false, i < visible.size() - 1));
            }
        }
        items.add(new Item(TYPE_FOOTER, 6, settings.who, null, false, false));
        items.add(new Item(TYPE_BOTTOM_SPACE, 7, owner ? 0 : 1, null, false, false));

        if (animated && isAttachedToWindow()) {
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return items.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).stableId == items.get(n).stableId;
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    Item a = old.get(o), b = items.get(n);
                    return a.contentHash == b.contentHash && a.divider == b.divider && bumpKey == null;
                }
            }).dispatchUpdatesTo(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        updateButton();
    }

    // ------------------------------------------------------------------ bottom button

    private void updateButton() {
        final boolean owner = controller.isOwner(ownerId);
        buttonContainer.setVisibility(owner ? GONE : VISIBLE);
        if (owner) return;
        ProfileCommentsController.LeaveState state = controller.getLeaveState(ownerId);
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (state.allowed) {
            text.append("+ ");
            text.setSpan(new org.telegram.ui.Components.ColoredImageSpan(R.drawable.msg_edit), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(state.text);
        button.setText(text, true);
        button.setAlpha(state.allowed ? 1f : .55f);
        updateButtonPosition();
    }

    private void updateButtonPosition() {
        if (buttonContainer.getVisibility() == GONE) return;
        float ty = -buttonContainer.getTop() + visibleHeight - buttonContainer.getMeasuredHeight();
        boolean show = visibleHeight > dp(184);
        buttonContainer.setTranslationY(ty + (show ? 0 : dp(80)));
        buttonContainer.setAlpha(show ? 1f : 0f);
    }

    private void onLeaveClick() {
        ProfileCommentsController.LeaveState state = controller.getLeaveState(ownerId);
        if (!state.allowed) {
            AndroidUtilities.shakeViewSpring(button, 3);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, state.text).show();
            return;
        }
        final ProfileCommentSheet[] sheet = new ProfileCommentSheet[1];
        sheet[0] = new ProfileCommentSheet(getContext(), currentAccount, ownerId, null, resourcesProvider, this::openOwnerChat, (text, tags) -> {
            ProfileCommentsController.Comment c = controller.post(ownerId, text, tags);
            if (c == null) {
                sheet[0].showError(controller.getLeaveState(ownerId).text);
                return false;
            }
            tagFilter = null;
            update(true);
            listView.smoothScrollToPosition(0);
            String first = controller.getOwnerFirstName(ownerId);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, c.status == ProfileCommentsController.STATUS_PENDING
                ? formatString(R.string.ProfileCommentsToastPending, first)
                : getString(R.string.ProfileCommentsToastPosted)).show();
            return true;
        });
        sheet[0].show();
    }

    private void openOwnerChat() {
        fragment.presentFragment(ChatActivity.of(ownerId));
    }

    // ------------------------------------------------------------------ comment actions

    @Override
    public void onCommentTap(ProfileCommentCell cell, ProfileCommentsController.Comment c) {
        final boolean owner = controller.isOwner(ownerId);
        final boolean mine = controller.isMine(c);
        final String authorFirst = controller.getAuthorFirstName(c);
        final ItemOptions o = ItemOptions.makeOptions(fragment, cell);

        if (c.status == ProfileCommentsController.STATUS_APPROVED) {
            // Fixed width: the strip scrolls horizontally instead of stretching the menu.
            o.addView(makeReactionsRow(o, cell, c), LayoutHelper.createLinear(250, 46));
            o.addGap();
        }
        if (owner) {
            if (c.status == ProfileCommentsController.STATUS_PENDING) {
                o.add(R.drawable.msg_select, getString(R.string.ProfileCommentsApprove), () -> onApprove(c));
            } else {
                o.add(R.drawable.menu_reply, getString(c.hasReply() ? R.string.ProfileCommentsEditReply : R.string.ProfileCommentsReply), () -> openReply(c));
                if (c.hasReply()) {
                    o.add(R.drawable.msg_cancel, getString(R.string.ProfileCommentsDeleteReply), () -> {
                        controller.setReply(ownerId, c, null);
                        toast(R.raw.ic_delete, getString(R.string.ProfileCommentsToastReplyDeleted));
                    });
                }
                if (c.status == ProfileCommentsController.STATUS_APPROVED) {
                    o.add(c.pinned ? R.drawable.msg_unpin : R.drawable.msg_pin, getString(c.pinned ? R.string.ProfileCommentsUnpin : R.string.ProfileCommentsPin), () -> {
                        boolean pinned = controller.togglePin(ownerId, c);
                        toast(pinned ? R.raw.ic_pin : R.raw.ic_unpin, getString(pinned ? R.string.ProfileCommentsToastPinned : R.string.ProfileCommentsToastUnpinned));
                        if (pinned) listView.smoothScrollToPosition(0);
                    });
                }
                final boolean hidden = c.status == ProfileCommentsController.STATUS_HIDDEN;
                o.add(hidden ? R.drawable.msg_views : R.drawable.msg_stories_myhide, getString(hidden ? R.string.ProfileCommentsShow : R.string.ProfileCommentsHide), () -> {
                    controller.setStatus(ownerId, c, hidden ? ProfileCommentsController.STATUS_APPROVED : ProfileCommentsController.STATUS_HIDDEN);
                    toast(R.raw.contact_check, getString(hidden ? R.string.ProfileCommentsToastShown : R.string.ProfileCommentsToastHidden));
                });
            }
        }
        if (!mine) {
            o.add(R.drawable.msg_message, formatString(R.string.ProfileCommentsMessage, authorFirst), () -> openAuthorChat(c));
        }
        o.add(R.drawable.msg_copy, getString(R.string.ProfileCommentsCopy), () -> {
            AndroidUtilities.addToClipboard(c.text);
            toast(R.raw.copy, getString(R.string.ProfileCommentsToastCopied));
        });
        if (!owner && !mine) {
            final boolean reported = controller.isReportedByMe(c);
            if (reported) {
                o.add(R.drawable.msg_report, getString(R.string.ProfileCommentsReported), () -> {});
            } else {
                o.add(R.drawable.msg_report, getString(R.string.ProfileCommentsReport), true, () -> {
                    controller.report(ownerId, c);
                    toast(R.raw.chats_infotip, formatString(R.string.ProfileCommentsToastReported, controller.getOwnerFirstName(ownerId)));
                });
            }
        }
        if (owner || mine) {
            o.addGap();
            final int label = owner && !mine
                ? (c.status == ProfileCommentsController.STATUS_PENDING ? R.string.ProfileCommentsDecline : R.string.ProfileCommentsDelete)
                : R.string.ProfileCommentsDeleteMine;
            o.add(R.drawable.msg_delete, getString(label), true, () -> {
                controller.delete(ownerId, c);
                toast(R.raw.ic_delete, getString(R.string.ProfileCommentsToastDeleted));
            });
        }
        o.setDimAlpha(0x66);
        o.show();
    }

    private View makeReactionsRow(ItemOptions o, ProfileCommentCell cell, ProfileCommentsController.Comment c) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        for (String emoji : ProfileCommentsController.REACTIONS) {
            TextView b = new TextView(getContext());
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            b.setGravity(Gravity.CENTER);
            b.setText(Emoji.replaceEmoji(emoji, b.getPaint().getFontMetricsInt(), false));
            if (controller.hasMyReaction(c, emoji)) {
                b.setBackground(Theme.createRoundRectDrawable(dp(19), Theme.multAlpha(accent, .15f)));
            }
            b.setContentDescription(emoji);
            b.setOnClickListener(v -> {
                o.dismiss();
                react(cell, c, emoji);
            });
            ScaleStateListAnimator.apply(b, .2f, 1.5f);
            row.addView(b, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL, 1, 4, 1, 4));
        }
        return scroll;
    }

    @Override
    public void onCommentDoubleTap(ProfileCommentCell cell, ProfileCommentsController.Comment c) {
        if (c.status != ProfileCommentsController.STATUS_APPROVED) return;
        react(cell, c, ProfileCommentsController.REACTIONS[0]);
    }

    @Override
    public void onReactionClick(ProfileCommentCell cell, ProfileCommentsController.Comment c, String emoji) {
        react(cell, c, emoji);
    }

    private void react(ProfileCommentCell cell, ProfileCommentsController.Comment c, String emoji) {
        if (c.status != ProfileCommentsController.STATUS_APPROVED) return;
        if (cell != null && cell.getComment() == c) {
            cell.bumpReaction(emoji);
            cell.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        bumpKey = emoji;
        controller.toggleReaction(ownerId, c, emoji);
        bumpKey = null;
    }

    @Override
    public void onApprove(ProfileCommentsController.Comment c) {
        controller.setStatus(ownerId, c, ProfileCommentsController.STATUS_APPROVED);
        toast(R.raw.contact_check, getString(R.string.ProfileCommentsToastApproved));
    }

    @Override
    public void onDismiss(ProfileCommentsController.Comment c) {
        controller.delete(ownerId, c);
        toast(R.raw.ic_delete, getString(R.string.ProfileCommentsToastDismissed));
    }

    private void openReply(ProfileCommentsController.Comment c) {
        new ProfileCommentSheet(getContext(), currentAccount, ownerId, c, resourcesProvider, null, (text, tags) -> {
            controller.setReply(ownerId, c, text);
            toast(R.raw.contact_check, getString(TextUtils.isEmpty(text) ? R.string.ProfileCommentsToastReplyDeleted : R.string.ProfileCommentsToastReply));
            return true;
        }).show();
    }

    private void openAuthorChat(ProfileCommentsController.Comment c) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(c.authorId);
        if (user == null) {
            toast(R.raw.chats_infotip, getString(R.string.ProfileCommentsSampleUser));
            return;
        }
        fragment.presentFragment(ChatActivity.of(c.authorId));
    }

    private void openSettings() {
        fragment.presentFragment(new ProfileCommentsSettingsActivity());
    }

    private void toast(int raw, CharSequence text) {
        BulletinFactory.of(fragment).createSimpleBulletin(raw, text).show();
    }

    // ------------------------------------------------------------------ adapter

    private class Adapter extends RecyclerListView.SelectionAdapter {

        Adapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).stableId;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case TYPE_FILTERS: {
                    HorizontalScrollView scroll = new HorizontalScrollView(context);
                    scroll.setHorizontalScrollBarEnabled(false);
                    scroll.setClipToPadding(false);
                    scroll.setPadding(dp(12), dp(10), dp(12), dp(4));
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    scroll.addView(row);
                    view = scroll;
                    break;
                }
                case TYPE_PENDING_HEADER:
                case TYPE_LIST_HEADER:
                    view = new HeaderView(context);
                    break;
                case TYPE_COMMENT:
                    view = new ProfileCommentCell(context, currentAccount, resourcesProvider, ProfileCommentsContainer.this);
                    break;
                case TYPE_EMPTY:
                    view = new EmptyView(context);
                    break;
                case TYPE_FOOTER: {
                    TextView tv = new TextView(context);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    tv.setGravity(Gravity.CENTER_HORIZONTAL);
                    tv.setPadding(dp(32), dp(14), dp(32), dp(14));
                    tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                    view = tv;
                    break;
                }
                case TYPE_GAP: {
                    view = new View(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
                    view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
                    return new RecyclerListView.Holder(view);
                }
                case TYPE_BOTTOM_SPACE:
                default:
                    view = new View(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Item item = items.get(position);
            final boolean owner = controller.isOwner(ownerId);
            final ProfileCommentsController.Settings settings = controller.getSettings(ownerId);
            final String ownerFirst = controller.getOwnerFirstName(ownerId);
            switch (item.type) {
                case TYPE_FILTERS:
                    bindFilters((LinearLayout) ((HorizontalScrollView) holder.itemView).getChildAt(0));
                    break;
                case TYPE_PENDING_HEADER: {
                    HeaderView h = (HeaderView) holder.itemView;
                    h.set(formatString(R.string.ProfileCommentsPendingHeader, item.contentHash), getString(R.string.ProfileCommentsPendingHint), Theme.key_color_orange, null);
                    break;
                }
                case TYPE_LIST_HEADER: {
                    HeaderView h = (HeaderView) holder.itemView;
                    int count = controller.approvedCount(ownerId);
                    String mode;
                    if (settings.who == ProfileCommentsController.WHO_NOBODY) mode = getString(R.string.ProfileCommentsModeOff);
                    else if (settings.who == ProfileCommentsController.WHO_CONTACTS) mode = getString(R.string.ProfileCommentsModeContacts);
                    else if (settings.approve) mode = getString(R.string.ProfileCommentsModeApproval);
                    else mode = getString(R.string.ProfileCommentsModePublic);
                    if (owner) {
                        h.set(formatPluralString("ProfileCommentsCount", count), mode + "  ·  " + getString(R.string.ProfileCommentsSettingsShort), -1, v -> openSettings());
                    } else {
                        h.set(formatPluralString("ProfileCommentsCount", count), mode, -1, null);
                    }
                    break;
                }
                case TYPE_COMMENT: {
                    ProfileCommentCell cell = (ProfileCommentCell) holder.itemView;
                    cell.set(ownerId, item.comment, item.pending, item.divider);
                    break;
                }
                case TYPE_EMPTY: {
                    EmptyView e = (EmptyView) holder.itemView;
                    String sub;
                    if (owner) sub = getString(R.string.ProfileCommentsEmptyOwner);
                    else if (settings.who == ProfileCommentsController.WHO_NOBODY) sub = formatString(R.string.ProfileCommentsEmptyOff, ownerFirst);
                    else sub = formatString(R.string.ProfileCommentsEmptyVisitor, ownerFirst);
                    e.set(sub);
                    break;
                }
                case TYPE_FOOTER: {
                    String prefix = "";
                    if (settings.who == ProfileCommentsController.WHO_NOBODY) prefix = formatString(R.string.ProfileCommentsRulesOff, ownerFirst);
                    else if (settings.who == ProfileCommentsController.WHO_CONTACTS) prefix = formatString(R.string.ProfileCommentsRulesContacts, ownerFirst);
                    String name = owner ? getString(R.string.ProfileCommentsYou).toLowerCase() : ownerFirst;
                    ((TextView) holder.itemView).setText(prefix + formatString(R.string.ProfileCommentsRules, name));
                    break;
                }
                case TYPE_BOTTOM_SPACE: {
                    ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                    lp.height = owner ? dp(24) : dp(76 + 16) + AndroidUtilities.navigationBarHeight;
                    holder.itemView.setLayoutParams(lp);
                    break;
                }
            }
        }
    }

    private void bindFilters(LinearLayout row) {
        row.removeAllViews();
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        addFilterChip(row, null, getString(R.string.ProfileCommentsFilterAll), controller.approvedCount(ownerId), accent, text, gray);
        for (Map.Entry<String, Integer> e : controller.getTagCounts(ownerId).entrySet()) {
            ProfileCommentsController.Tag tag = ProfileCommentsController.findTag(e.getKey());
            if (tag == null) continue;
            addFilterChip(row, tag.id, tag.emoji + " " + getString(tag.titleRes), e.getValue(), accent, text, gray);
        }
    }

    private void addFilterChip(LinearLayout row, String id, String label, int count, int accent, int text, int gray) {
        final boolean on = Objects.equals(id, tagFilter);
        TextView chip = new TextView(getContext());
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        SpannableStringBuilder sb = new SpannableStringBuilder(label).append("  ");
        int start = sb.length();
        sb.append(String.valueOf(count));
        sb.setSpan(new ForegroundColorSpan(on ? accent : gray), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        chip.setText(Emoji.replaceEmoji(sb, chip.getPaint().getFontMetricsInt(), false));
        chip.setTextColor(on ? accent : text);
        chip.setTypeface(on ? AndroidUtilities.bold() : null);
        chip.setBackground(Theme.createRoundRectDrawable(dp(16), on ? Theme.multAlpha(accent, .14f) : Theme.multAlpha(gray, .12f)));
        chip.setOnClickListener(v -> {
            if (Objects.equals(id, tagFilter)) return;
            tagFilter = id;
            update(true);
        });
        ScaleStateListAnimator.apply(chip, .05f, 1.4f);
        row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
    }

    /** Section title with a secondary text on the right. */
    private class HeaderView extends LinearLayout {
        private final TextView title;
        private final TextView value;

        HeaderView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(14), dp(16), dp(6));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            value.setPadding(dp(8), dp(4), dp(8), dp(4));
            addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, -8, 0));
        }

        void set(CharSequence t, CharSequence v, int titleColorKey, View.OnClickListener onValueClick) {
            title.setText(t);
            title.setTextColor(Theme.getColor(titleColorKey >= 0 ? titleColorKey : Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            value.setText(v);
            value.setTextColor(Theme.getColor(onValueClick != null ? Theme.key_windowBackgroundWhiteBlueText4 : Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            value.setOnClickListener(onValueClick);
            value.setClickable(onValueClick != null);
            value.setBackground(onValueClick != null ? Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 8, 8) : null);
        }
    }

    private class EmptyView extends LinearLayout {
        private final TextView subtitle;

        EmptyView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(32), dp(28), dp(32), dp(20));
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.msg_discussion);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider), android.graphics.PorterDuff.Mode.SRC_IN));
            addView(icon, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL));
            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            title.setText(getString(R.string.ProfileCommentsEmptyTitle));
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        }

        void set(CharSequence sub) {
            subtitle.setText(sub);
        }
    }
}
