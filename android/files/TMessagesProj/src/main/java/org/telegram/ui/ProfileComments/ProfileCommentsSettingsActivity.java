package org.telegram.ui.ProfileComments;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** Settings → Privacy and Security → Profile Comments. */
public class ProfileCommentsSettingsActivity extends BaseFragment implements ProfileCommentsController.Listener {

    private static final int VIEW_HEADER = 0;
    private static final int VIEW_RADIO = 1;
    private static final int VIEW_INFO = 2;
    private static final int VIEW_CHECK = 3;
    private static final int VIEW_FEATURE = 4;
    private static final int VIEW_SHADOW = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private int rowCount;
    private int whoHeaderRow, everybodyRow, contactsRow, nobodyRow, whoInfoRow;
    private int moderationHeaderRow, approveRow, approveInfoRow;
    private int howHeaderRow, how1Row, how2Row, how3Row, howShadowRow;

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        ProfileCommentsController.getInstance(currentAccount).addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        ProfileCommentsController.getInstance(currentAccount).removeListener(this);
        super.onFragmentDestroy();
    }

    private void updateRows() {
        rowCount = 0;
        whoHeaderRow = rowCount++;
        everybodyRow = rowCount++;
        contactsRow = rowCount++;
        nobodyRow = rowCount++;
        whoInfoRow = rowCount++;
        moderationHeaderRow = rowCount++;
        approveRow = rowCount++;
        approveInfoRow = rowCount++;
        howHeaderRow = rowCount++;
        how1Row = rowCount++;
        how2Row = rowCount++;
        how3Row = rowCount++;
        howShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ProfileCommentsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            final ProfileCommentsController controller = ProfileCommentsController.getInstance(currentAccount);
            final ProfileCommentsController.Settings s = controller.getSettings(controller.selfId());
            if (position == everybodyRow || position == contactsRow || position == nobodyRow) {
                int who = position == everybodyRow ? ProfileCommentsController.WHO_EVERYBODY : position == contactsRow ? ProfileCommentsController.WHO_CONTACTS : ProfileCommentsController.WHO_NOBODY;
                if (who != s.who) controller.setMySettings(who, s.approve);
            } else if (position == approveRow) {
                controller.setMySettings(s.who, !s.approve);
                ((TextCheckCell) view).setChecked(!s.approve);
            }
        });
        return fragmentView;
    }

    @Override
    public void onProfileCommentsUpdated(long ownerId) {
        if (listView == null) return;
        // Refresh radios and the hint below them without rebuilding the list (keeps radio animations).
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            int position = listView.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            if (child instanceof RadioCell) {
                ((RadioCell) child).setChecked(isWhoChecked(position), true);
            } else if (position == whoInfoRow) {
                adapter.onBindViewHolder(listView.getChildViewHolder(child), position);
            }
        }
    }

    private boolean isWhoChecked(int position) {
        final ProfileCommentsController controller = ProfileCommentsController.getInstance(currentAccount);
        int who = controller.getSettings(controller.selfId()).who;
        return position == everybodyRow && who == ProfileCommentsController.WHO_EVERYBODY
            || position == contactsRow && who == ProfileCommentsController.WHO_CONTACTS
            || position == nobodyRow && who == ProfileCommentsController.WHO_NOBODY;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int p = holder.getAdapterPosition();
            return p == everybodyRow || p == contactsRow || p == nobodyRow || p == approveRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == whoHeaderRow || position == moderationHeaderRow || position == howHeaderRow) return VIEW_HEADER;
            if (position == everybodyRow || position == contactsRow || position == nobodyRow) return VIEW_RADIO;
            if (position == approveRow) return VIEW_CHECK;
            if (position == how1Row || position == how2Row || position == how3Row) return VIEW_FEATURE;
            if (position == howShadowRow) return VIEW_SHADOW;
            return VIEW_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_RADIO:
                    view = new RadioCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_CHECK:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_FEATURE:
                    view = new FeatureCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                case VIEW_INFO:
                default:
                    view = new TextInfoPrivacyCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final ProfileCommentsController controller = ProfileCommentsController.getInstance(currentAccount);
            final ProfileCommentsController.Settings s = controller.getSettings(controller.selfId());
            switch (holder.getItemViewType()) {
                case VIEW_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == whoHeaderRow) cell.setText(getString(R.string.ProfileCommentsWhoHeader));
                    else if (position == moderationHeaderRow) cell.setText(getString(R.string.ProfileCommentsModerationHeader));
                    else cell.setText(getString(R.string.ProfileCommentsHowHeader));
                    break;
                }
                case VIEW_RADIO: {
                    RadioCell cell = (RadioCell) holder.itemView;
                    if (position == everybodyRow) cell.setText(getString(R.string.ProfileCommentsWhoEverybody), isWhoChecked(position), true);
                    else if (position == contactsRow) cell.setText(getString(R.string.ProfileCommentsWhoContacts), isWhoChecked(position), true);
                    else cell.setText(getString(R.string.ProfileCommentsWhoNobody), isWhoChecked(position), false);
                    break;
                }
                case VIEW_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(getString(R.string.ProfileCommentsApproveNew), s.approve, false);
                    break;
                }
                case VIEW_FEATURE: {
                    FeatureCell cell = (FeatureCell) holder.itemView;
                    if (position == how1Row) cell.set(R.drawable.msg_recent, getString(R.string.ProfileCommentsHow1), getString(R.string.ProfileCommentsHow1Sub), true);
                    else if (position == how2Row) cell.set(R.drawable.menu_reply, getString(R.string.ProfileCommentsHow2), getString(R.string.ProfileCommentsHow2Sub), true);
                    else cell.set(R.drawable.msg_pin, getString(R.string.ProfileCommentsHow3), getString(R.string.ProfileCommentsHow3Sub), false);
                    break;
                }
                case VIEW_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == whoInfoRow) {
                        int res = s.who == ProfileCommentsController.WHO_NOBODY ? R.string.ProfileCommentsWhoInfoNobody
                            : s.who == ProfileCommentsController.WHO_CONTACTS ? R.string.ProfileCommentsWhoInfoContacts
                            : R.string.ProfileCommentsWhoInfoEverybody;
                        cell.setText(getString(res));
                        cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else {
                        cell.setText(getString(R.string.ProfileCommentsApproveInfo));
                        cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
            }
        }
    }

    /** Icon + title + subtitle row, used for "How comments work". */
    private static class FeatureCell extends LinearLayout {
        private final ImageView icon;
        private final TextView title;
        private final TextView subtitle;
        private boolean divider;

        FeatureCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);
            setPadding(dp(21), dp(10), dp(16), dp(10));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon), PorterDuff.Mode.SRC_IN));
            addView(icon, LayoutHelper.createLinear(24, 24, Gravity.TOP, 0, 6, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 30, 0, 0, 0));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            texts.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void set(int iconRes, CharSequence t, CharSequence s, boolean divider) {
            icon.setImageResource(iconRes);
            title.setText(t);
            subtitle.setText(s);
            this.divider = divider;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (divider) {
                canvas.drawLine(dp(75), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
