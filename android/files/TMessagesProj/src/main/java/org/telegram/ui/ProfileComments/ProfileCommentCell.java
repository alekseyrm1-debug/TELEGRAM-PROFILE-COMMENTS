package org.telegram.ui.ProfileComments;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;

/** One comment on the wall: author, tags, text, reactions and the owner's reply. */
@SuppressLint("ViewConstructor")
public class ProfileCommentCell extends FrameLayout {

    public interface Delegate {
        void onCommentTap(ProfileCommentCell cell, ProfileCommentsController.Comment comment);
        void onCommentDoubleTap(ProfileCommentCell cell, ProfileCommentsController.Comment comment);
        void onReactionClick(ProfileCommentCell cell, ProfileCommentsController.Comment comment, String emoji);
        void onApprove(ProfileCommentsController.Comment comment);
        void onDismiss(ProfileCommentsController.Comment comment);
    }

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final GestureDetector gestureDetector;

    private final BackupImageView avatarView;
    private final AvatarDrawable avatarDrawable;
    private final TextView nameView;
    private final ImageView pinView;
    private final TextView dateView;
    private final TextView tagsView;
    private final TextView statusView;
    private final TextView textView;
    private final LinearLayout reactionsLayout;
    private final LinearLayout replyLayout;
    private final BackupImageView replyAvatarView;
    private final AvatarDrawable replyAvatarDrawable;
    private final TextView replyTitleView;
    private final TextView replyTextView;
    private final LinearLayout moderationLayout;

    private ProfileCommentsController.Comment comment;
    private long ownerId;
    private boolean needDivider;
    private String bumpEmoji;

    public ProfileCommentCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        setWillNotDraw(false);

        avatarDrawable = new AvatarDrawable(resourcesProvider);
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(20));
        addView(avatarView, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.TOP, 16, 12, 0, 0));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 68, 11, 16, 12));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setSingleLine();
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        pinView = new ImageView(context);
        pinView.setImageResource(R.drawable.msg_pin);
        pinView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        top.addView(pinView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 6, 0, 3, 0));

        dateView = new TextView(context);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        dateView.setSingleLine();
        top.addView(dateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        tagsView = new TextView(context);
        tagsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        column.addView(tagsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setPadding(dp(7), dp(2), dp(7), dp(2));
        column.addView(statusView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setLineSpacing(dp(1.5f), 1f);
        column.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        reactionsLayout = new LinearLayout(context);
        reactionsLayout.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(reactionsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        replyLayout = new LinearLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                // accent line on the left, like a quote
                Theme.dialogs_onlineCirclePaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
                AndroidUtilities.rectTmp.set(0, 0, dp(3), getHeight());
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(1.5f), dp(1.5f), Theme.dialogs_onlineCirclePaint);
                super.dispatchDraw(canvas);
            }
        };
        replyLayout.setOrientation(LinearLayout.VERTICAL);
        replyLayout.setPadding(dp(11), dp(7), dp(10), dp(8));
        column.addView(replyLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 9, 0, 0));

        LinearLayout replyTop = new LinearLayout(context);
        replyTop.setOrientation(LinearLayout.HORIZONTAL);
        replyTop.setGravity(Gravity.CENTER_VERTICAL);
        replyLayout.addView(replyTop, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        replyAvatarDrawable = new AvatarDrawable(resourcesProvider);
        replyAvatarDrawable.setTextSize(dp(9));
        replyAvatarView = new BackupImageView(context);
        replyAvatarView.setRoundRadius(dp(9));
        replyTop.addView(replyAvatarView, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        replyTitleView = new TextView(context);
        replyTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        replyTitleView.setSingleLine();
        replyTitleView.setEllipsize(TextUtils.TruncateAt.END);
        replyTop.addView(replyTitleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        replyTextView = new TextView(context);
        replyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        replyLayout.addView(replyTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        moderationLayout = new LinearLayout(context);
        moderationLayout.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(moderationLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 10, 0, 0));
        moderationLayout.addView(makeModButton(context, true), LayoutHelper.createLinear(0, 36, 1f, 0, 0, 4, 0));
        moderationLayout.addView(makeModButton(context, false), LayoutHelper.createLinear(0, 36, 1f, 4, 0, 0, 0));

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (comment != null) delegate.onCommentTap(ProfileCommentCell.this, comment);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (comment != null) delegate.onCommentDoubleTap(ProfileCommentCell.this, comment);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (comment != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    delegate.onCommentTap(ProfileCommentCell.this, comment);
                }
            }
        });

        updateColors();
    }

    private TextView makeModButton(Context context, boolean approve) {
        TextView b = new TextView(context);
        b.setTag(approve);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        b.setTypeface(AndroidUtilities.bold());
        b.setText(LocaleController.getString(approve ? R.string.ProfileCommentsApprove : R.string.ProfileCommentsDismiss));
        b.setOnClickListener(v -> {
            if (comment == null) return;
            if (approve) delegate.onApprove(comment);
            else delegate.onDismiss(comment);
        });
        ScaleStateListAnimator.apply(b, .04f, 1.4f);
        return b;
    }

    private void updateColors() {
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        nameView.setTextColor(black);
        dateView.setTextColor(gray);
        pinView.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        tagsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        textView.setTextColor(black);
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        replyLayout.setBackground(Theme.createRoundRectDrawable(dp(6), Theme.multAlpha(accent, .08f)));
        replyTitleView.setTextColor(accent);
        replyTextView.setTextColor(black);
        for (int i = 0; i < moderationLayout.getChildCount(); i++) {
            TextView b = (TextView) moderationLayout.getChildAt(i);
            boolean approve = (Boolean) b.getTag();
            int color = approve ? accent : Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
            b.setTextColor(approve ? Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider) : color);
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), approve ? accent : Theme.multAlpha(color, .10f), Theme.multAlpha(approve ? 0xFFFFFFFF : color, .2f)));
        }
    }

    public ProfileCommentsController.Comment getComment() {
        return comment;
    }

    /** Next bind animates the pill for this reaction. */
    public void bumpReaction(String emoji) {
        bumpEmoji = emoji;
    }

    public void set(long ownerId, ProfileCommentsController.Comment c, boolean pendingSection, boolean divider) {
        final ProfileCommentsController controller = ProfileCommentsController.getInstance(currentAccount);
        final boolean ownerView = controller.isOwner(ownerId);
        this.ownerId = ownerId;
        this.comment = c;
        this.needDivider = divider;

        // author
        TLRPC.User author = MessagesController.getInstance(currentAccount).getUser(c.authorId);
        if (author != null) {
            avatarDrawable.setInfo(currentAccount, author);
            avatarView.setForUserOrChat(author, avatarDrawable);
        } else {
            String name = controller.getAuthorName(c);
            int space = name.indexOf(' ');
            avatarDrawable.setInfo(Math.abs(c.authorId), space > 0 ? name.substring(0, space) : name, space > 0 ? name.substring(space + 1) : null);
            avatarView.setImageDrawable(avatarDrawable);
        }
        nameView.setText(Emoji.replaceEmoji(controller.isMine(c) && !ownerView ? LocaleController.getString(R.string.ProfileCommentsYou) : controller.getAuthorName(c), nameView.getPaint().getFontMetricsInt(), false));
        dateView.setText(ProfileCommentsController.formatAgo(c.date));
        pinView.setVisibility(c.pinned && c.status == ProfileCommentsController.STATUS_APPROVED ? VISIBLE : GONE);

        CharSequence tags = ProfileCommentsController.tagLine(c);
        tagsView.setVisibility(tags.length() > 0 ? VISIBLE : GONE);
        tagsView.setText(Emoji.replaceEmoji(tags, tagsView.getPaint().getFontMetricsInt(), false));

        // status chip
        CharSequence status = null;
        int statusColor = 0;
        if (c.status == ProfileCommentsController.STATUS_PENDING && !pendingSection) {
            status = LocaleController.getString(R.string.ProfileCommentsAwaiting);
            statusColor = Theme.getColor(Theme.key_color_orange, resourcesProvider);
        } else if (c.status == ProfileCommentsController.STATUS_HIDDEN) {
            status = LocaleController.getString(R.string.ProfileCommentsHiddenLabel);
            statusColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        } else if (ownerView && !c.reports.isEmpty()) {
            status = LocaleController.formatPluralString("ProfileCommentsReports", c.reports.size());
            statusColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
        }
        statusView.setVisibility(status != null ? VISIBLE : GONE);
        if (status != null) {
            statusView.setText(status);
            statusView.setTextColor(statusColor);
            statusView.setBackground(Theme.createRoundRectDrawable(dp(6), Theme.multAlpha(statusColor, .12f)));
        }

        textView.setText(Emoji.replaceEmoji(c.text, textView.getPaint().getFontMetricsInt(), false));
        float dim = c.status == ProfileCommentsController.STATUS_APPROVED || pendingSection ? 1f : .55f;
        textView.setAlpha(dim);
        tagsView.setAlpha(dim);
        avatarView.setAlpha(dim);

        bindReactions(controller, c);

        // owner reply
        replyLayout.setVisibility(c.hasReply() ? VISIBLE : GONE);
        if (c.hasReply()) {
            TLRPC.User owner = MessagesController.getInstance(currentAccount).getUser(ownerId);
            replyAvatarDrawable.setInfo(currentAccount, owner);
            replyAvatarView.setForUserOrChat(owner, replyAvatarDrawable);
            String ownerName = owner != null ? UserObject.getUserName(owner) : "";
            replyTitleView.setText(Emoji.replaceEmoji(LocaleController.formatString(R.string.ProfileCommentsReplyTitle, ownerName, ProfileCommentsController.formatAgo(c.replyDate)), replyTitleView.getPaint().getFontMetricsInt(), false));
            replyTextView.setText(Emoji.replaceEmoji(c.replyText, replyTextView.getPaint().getFontMetricsInt(), false));
        }

        moderationLayout.setVisibility(pendingSection ? VISIBLE : GONE);
        setContentDescription(controller.getAuthorName(c) + ", " + ProfileCommentsController.formatAgo(c.date) + ": " + c.text);
        requestLayout();
    }

    private void bindReactions(ProfileCommentsController controller, ProfileCommentsController.Comment c) {
        reactionsLayout.removeAllViews();
        ArrayList<String> sorted = c.status == ProfileCommentsController.STATUS_APPROVED ? c.sortedReactions() : new ArrayList<>();
        reactionsLayout.setVisibility(sorted.isEmpty() ? GONE : VISIBLE);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        for (String emoji : sorted) {
            final boolean mine = controller.hasMyReaction(c, emoji);
            TextView pill = new TextView(getContext());
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            pill.setTypeface(AndroidUtilities.bold());
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(8), 0, dp(9), 0);
            pill.setTextColor(mine ? Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider) : accent);
            pill.setBackground(Theme.createRoundRectDrawable(dp(14), mine ? accent : Theme.multAlpha(accent, .10f)));
            pill.setText(Emoji.replaceEmoji(emoji + " " + c.reactionCount(emoji), pill.getPaint().getFontMetricsInt(), false));
            pill.setContentDescription(emoji + " " + c.reactionCount(emoji));
            pill.setOnClickListener(v -> delegate.onReactionClick(this, c, emoji));
            ScaleStateListAnimator.apply(pill, .1f, 1.5f);
            reactionsLayout.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, 0, 0, 6, 0));
            if (emoji.equals(bumpEmoji)) {
                pill.setScaleX(.6f);
                pill.setScaleY(.6f);
                pill.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
        }
        bumpEmoji = null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            android.graphics.Paint dividerPaint = Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider);
            canvas.drawRect(dp(68), getHeight() - 1, getWidth(), getHeight(), dividerPaint);
        }
    }
}
