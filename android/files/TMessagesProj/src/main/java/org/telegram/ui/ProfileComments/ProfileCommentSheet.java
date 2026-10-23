package org.telegram.ui.ProfileComments;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * Bottom sheet used for both writing a comment (visitor) and replying to one (owner).
 */
public class ProfileCommentSheet extends BottomSheet {

    public interface Callback {
        /** Called with the trimmed text and selected tag ids. Return false to keep the sheet open. */
        boolean onDone(String text, ArrayList<String> tags);
    }

    private final int currentAccount;
    private final boolean replyMode;
    private final ArrayList<String> selectedTags = new ArrayList<>();
    private final FlowLayout tagsLayout;
    private final EditTextBoldCursor editText;
    private final TextView counterView;
    private final TextView errorView;
    private final ButtonWithCounterView button;
    private final Callback callback;

    /**
     * @param ownerId   profile owner
     * @param replyTo   null to write a new comment, otherwise the comment the owner replies to
     * @param onMessage opens a private chat with the owner (comment mode only)
     */
    public ProfileCommentSheet(Context context, int currentAccount, long ownerId, ProfileCommentsController.Comment replyTo,
                               Theme.ResourcesProvider resourcesProvider, Runnable onMessage, Callback callback) {
        super(context, true, resourcesProvider);
        this.currentAccount = currentAccount;
        this.replyMode = replyTo != null;
        this.callback = callback;
        smoothKeyboardAnimationEnabled = true;
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        final ProfileCommentsController controller = ProfileCommentsController.getInstance(currentAccount);
        final String ownerFirst = controller.getOwnerFirstName(ownerId);
        final int black = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int gray = Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(4), 0, 0);

        // ---- header: avatar + title + subtitle
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 10, 20, 14));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(20));
        AvatarDrawable avatarDrawable = new AvatarDrawable(resourcesProvider);
        // comment: the writer (you). reply: the owner (also you).
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        avatarDrawable.setInfo(currentAccount, me);
        avatar.setForUserOrChat(me, avatarDrawable);
        header.addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(black);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(gray);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        final String fieldLabel;
        final String hint;
        if (replyMode) {
            String author = controller.getAuthorFirstName(replyTo);
            title.setText(Emoji.replaceEmoji(formatString(replyTo.hasReply() ? R.string.ProfileCommentsEditReplyTo : R.string.ProfileCommentsReplyTo, author), title.getPaint().getFontMetricsInt(), false));
            String quote = replyTo.text.length() > 70 ? replyTo.text.substring(0, 70) + "…" : replyTo.text;
            subtitle.setText(Emoji.replaceEmoji("“" + quote + "”", subtitle.getPaint().getFontMetricsInt(), false));
            fieldLabel = getString(R.string.ProfileCommentsReplyField);
            hint = formatString(R.string.ProfileCommentsReplyHint, author);
        } else {
            title.setText(Emoji.replaceEmoji(formatString(R.string.ProfileCommentsSheetTitle, ownerFirst), title.getPaint().getFontMetricsInt(), false));
            subtitle.setText(controller.getSettings(ownerId).approve ? formatString(R.string.ProfileCommentsSheetSubApproval, ownerFirst) : getString(R.string.ProfileCommentsSheetSubPublic));
            fieldLabel = getString(R.string.ProfileCommentsSheetField);
            hint = formatString(R.string.ProfileCommentsSheetHint, ownerFirst);
        }

        // ---- tags (comment mode only)
        tagsLayout = new FlowLayout(context, dp(8), dp(8));
        if (!replyMode) {
            TextView tagsLabel = new TextView(context);
            tagsLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tagsLabel.setTypeface(AndroidUtilities.bold());
            tagsLabel.setTextColor(black);
            tagsLabel.setText(formatString(R.string.ProfileCommentsSheetTags, ownerFirst));
            layout.addView(tagsLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));
            layout.addView(tagsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 16));
            for (ProfileCommentsController.Tag tag : ProfileCommentsController.TAGS) {
                TextView chip = new TextView(context);
                chip.setTag(tag.id);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(12), 0, dp(12), 0);
                chip.setText(Emoji.replaceEmoji(tag.emoji + "  " + getString(tag.titleRes), chip.getPaint().getFontMetricsInt(), false));
                chip.setOnClickListener(v -> {
                    if (selectedTags.contains(tag.id)) {
                        selectedTags.remove(tag.id);
                    } else if (selectedTags.size() < ProfileCommentsController.MAX_TAGS) {
                        selectedTags.add(tag.id);
                    } else {
                        AndroidUtilities.shakeViewSpring(v, 3);
                        return;
                    }
                    updateChips();
                });
                ScaleStateListAnimator.apply(chip, .05f, 1.4f);
                tagsLayout.addView(chip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
            }
            updateChips();
        }

        // ---- text field
        OutlineTextContainerView outline = new OutlineTextContainerView(context, resourcesProvider);
        outline.setText(fieldLabel);
        editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(black);
        editText.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHintText(hint);
        editText.setCursorColor(accent);
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setBackground(null);
        editText.setGravity(Gravity.TOP | Gravity.LEFT);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setMinLines(3);
        editText.setMaxLines(6);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ProfileCommentsController.MAX_LENGTH)});
        editText.setPadding(dp(16), dp(16), dp(16), dp(26));
        outline.attachEditText(editText);
        outline.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        editText.setOnFocusChangeListener((v, hasFocus) -> outline.animateSelection(hasFocus, !TextUtils.isEmpty(editText.getText())));

        counterView = new TextView(context);
        counterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        counterView.setTextColor(gray);
        outline.addView(counterView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 12, 8));
        layout.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        if (replyMode && replyTo.hasReply()) {
            editText.setText(replyTo.replyText);
            editText.setSelection(editText.length());
            outline.animateSelection(true, true, false);
        }

        // ---- note
        LinearLayout note = new LinearLayout(context);
        note.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));
        ImageView noteIcon = new ImageView(context);
        noteIcon.setImageResource(replyMode ? R.drawable.msg_mini_lock3 : R.drawable.msg_recent);
        noteIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(gray, android.graphics.PorterDuff.Mode.SRC_IN));
        noteIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        note.addView(noteIcon, LayoutHelper.createLinear(18, 18, Gravity.TOP, 0, 1, 8, 0));
        TextView noteText = new TextView(context);
        noteText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        noteText.setTextColor(gray);
        noteText.setLinkTextColor(accent);
        if (replyMode) {
            noteText.setText(formatString(R.string.ProfileCommentsReplyNote, controller.getAuthorFirstName(replyTo)));
        } else {
            noteText.setText(AndroidUtilities.replaceSingleTag(
                formatString(R.string.ProfileCommentsSheetNote, ownerFirst),
                -1, AndroidUtilities.REPLACING_TAG_TYPE_LINKBOLD, () -> {
                    dismiss();
                    if (onMessage != null) onMessage.run();
                }, resourcesProvider));
            noteText.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        }
        note.addView(noteText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        errorView.setVisibility(View.GONE);
        layout.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 0));

        // ---- button
        button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString(replyMode ? R.string.ProfileCommentsReplyPost : R.string.ProfileCommentsSheetPost), false);
        button.setOnClickListener(v -> submit());
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 8));

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateCounter();
            }
        });
        updateCounter();

        setCustomView(layout);
    }

    private void updateChips() {
        final boolean full = selectedTags.size() >= ProfileCommentsController.MAX_TAGS;
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int text = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int gray = Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider);
        for (int i = 0; i < tagsLayout.getChildCount(); i++) {
            TextView chip = (TextView) tagsLayout.getChildAt(i);
            boolean on = selectedTags.contains((String) chip.getTag());
            chip.setTextColor(on ? accent : text);
            chip.setTypeface(on ? AndroidUtilities.bold() : null);
            chip.setBackground(Theme.createRoundRectDrawable(dp(17), on ? Theme.multAlpha(accent, .14f) : Theme.multAlpha(gray, .12f)));
            chip.setAlpha(!on && full ? .45f : 1f);
            chip.setSelected(on);
        }
    }

    private void updateCounter() {
        int len = editText.length();
        int left = ProfileCommentsController.MAX_LENGTH - len;
        counterView.setText(String.valueOf(left));
        counterView.setTextColor(left < 20 ? Theme.getColor(Theme.key_text_RedRegular, resourcesProvider) : Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        boolean enabled = editText.getText().toString().trim().length() >= ProfileCommentsController.MIN_LENGTH || replyMode;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .5f);
        errorView.setVisibility(View.GONE);
    }

    private void submit() {
        String text = editText.getText().toString().trim();
        if (!replyMode && text.length() < ProfileCommentsController.MIN_LENGTH) {
            AndroidUtilities.shakeViewSpring(editText, 4);
            return;
        }
        if (callback.onDone(text, new ArrayList<>(selectedTags))) {
            AndroidUtilities.hideKeyboard(editText);
            dismiss();
        }
    }

    public void showError(CharSequence error) {
        errorView.setText(error);
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    public void show() {
        super.show();
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 250);
    }

    /** Wraps children onto new lines, used for the tag chips. */
    private static class FlowLayout extends FrameLayout {
        private final int hGap, vGap;

        FlowLayout(Context context, int hGap, int vGap) {
            super(context);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            int x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(child.getLayoutParams().height, MeasureSpec.EXACTLY));
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    x = 0;
                    y += rowHeight + vGap;
                    rowHeight = 0;
                }
                x += child.getMeasuredWidth() + hGap;
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowHeight);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            final int width = right - left;
            int x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    x = 0;
                    y += rowHeight + vGap;
                    rowHeight = 0;
                }
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + hGap;
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
        }
    }
}
