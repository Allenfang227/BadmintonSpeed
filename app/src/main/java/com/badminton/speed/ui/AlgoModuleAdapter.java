package com.badminton.speed.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.badminton.speed.R;

import java.util.List;

/**
 * 右侧算法模块状态卡片的 RecyclerView Adapter。
 * 每个模块展示标题、进度、状态，以及细化的子步骤（带状态图标）。
 */
public class AlgoModuleAdapter extends RecyclerView.Adapter<AlgoModuleAdapter.VH> {

    /** 子步骤状态 */
    public static final int SUB_PENDING = 0;   // 未开始（灰色○）
    public static final int SUB_RUNNING = 1;   // 进行中（橙色●）
    public static final int SUB_PASS = 2;      // 通过（绿色✓）
    public static final int SUB_FAIL = 3;      // 失败（红色✗）

    public static class ModuleItem {
        public String title;
        public String desc;
        public String[] subItems;
        public int[] subStatuses;    // 与 subItems 一一对应
        public String status;        // "未检测" / "检测中 xx%" / "通过 ✅" / "未通过 ❌" / "警告⚠"
        public int progress;         // 0-100
        public int iconRes;          // 模块图标资源
        public boolean expanded = true; // 默认展开
    }

    public final List<ModuleItem> items;

    public AlgoModuleAdapter(List<ModuleItem> items) {
        this.items = items;
        for (ModuleItem m : items) initSubStatuses(m);
    }

    private void initSubStatuses(ModuleItem m) {
        if (m.subItems == null) return;
        if (m.subStatuses == null || m.subStatuses.length != m.subItems.length) {
            m.subStatuses = new int[m.subItems.length];
            for (int i = 0; i < m.subItems.length; i++) m.subStatuses[i] = SUB_PENDING;
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_algo_module, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        ModuleItem m = items.get(pos);
        if (m.iconRes != 0) h.ivModuleIcon.setImageResource(m.iconRes);
        h.tvTitle.setText(m.title);
        h.tvDesc.setText(m.desc);
        h.tvStatus.setText(m.status);
        h.progressBar.setProgress(Math.max(0, Math.min(100, m.progress)));

        // 状态着色
        String s = m.status == null ? "" : m.status;
        if (s.contains("通过") || s.contains("Done") || s.contains("✅")) {
            h.tvStatus.setTextColor(Color.parseColor("#39FF14"));
            h.tvStatus.setBackgroundColor(Color.parseColor("#1A39FF14"));
        } else if (s.contains("未通过") || s.contains("❌") || s.contains("失败")) {
            h.tvStatus.setTextColor(Color.parseColor("#FF4444"));
            h.tvStatus.setBackgroundColor(Color.parseColor("#1AFF4444"));
        } else if (s.contains("警告") || s.contains("⚠")) {
            h.tvStatus.setTextColor(Color.parseColor("#FFB020"));
            h.tvStatus.setBackgroundColor(Color.parseColor("#1AFFB020"));
        } else if (s.contains("检测中") || s.contains("进行")) {
            h.tvStatus.setTextColor(Color.parseColor("#FFB020"));
            h.tvStatus.setBackgroundColor(Color.parseColor("#1AFFB020"));
        } else {
            h.tvStatus.setTextColor(Color.parseColor("#7A8A80"));
            h.tvStatus.setBackgroundColor(Color.parseColor("#1A7A8A80"));
        }

        // 子项展开
        h.subContainer.setVisibility(m.expanded ? View.VISIBLE : View.GONE);
        h.ivExpand.setRotation(m.expanded ? 0 : 180);
        h.itemView.setOnClickListener(v -> {
            m.expanded = !m.expanded;
            notifyItemChanged(pos);
        });

        // 动态渲染子步骤行（只在首次创建时建视图，后续只更新图标颜色）
        renderSubItems(h, m);
    }

    private void renderSubItems(VH h, ModuleItem m) {
        // 如果子项视图已经创建过且数量匹配，只更新状态，不重建
        if (h.subIcons != null && h.subIcons.length == (m.subItems != null ? m.subItems.length : 0)) {
            updateSubItemStatuses(h, m);
            return;
        }

        h.subContainer.removeAllViews();
        if (m.subItems == null || m.subItems.length == 0) {
            h.subIcons = null;
            h.subTexts = null;
            return;
        }

        int n = m.subItems.length;
        h.subIcons = new ImageView[n];
        h.subTexts = new TextView[n];

        int cols = 2;
        LinearLayout rowLayout = null;
        for (int i = 0; i < n; i++) {
            if (i % cols == 0) {
                rowLayout = new LinearLayout(h.subContainer.getContext());
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                h.subContainer.addView(rowLayout);
            }

            LinearLayout item = new LinearLayout(h.subContainer.getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 4, 8, 4);
            item.setLayoutParams(lp);

            ImageView icon = new ImageView(h.subContainer.getContext());
            int size = (int) (16 * h.subContainer.getResources().getDisplayMetrics().density);
            icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));

            TextView tv = new TextView(h.subContainer.getContext());
            tv.setText(m.subItems[i]);
            tv.setTextSize(11f);
            tv.setPadding(8, 0, 0, 0);

            h.subIcons[i] = icon;
            h.subTexts[i] = tv;

            item.addView(icon);
            item.addView(tv);
            if (rowLayout != null) rowLayout.addView(item);
        }

        updateSubItemStatuses(h, m);
    }

    /** 只更新子项图标和颜色，不重建视图（避免高频 notifyItemChanged 卡死） */
    private void updateSubItemStatuses(VH h, ModuleItem m) {
        if (h.subIcons == null || m.subStatuses == null) return;
        for (int i = 0; i < h.subIcons.length && i < m.subStatuses.length; i++) {
            int status = m.subStatuses[i];
            ImageView icon = h.subIcons[i];
            TextView tv = h.subTexts[i];
            switch (status) {
                case SUB_PASS:
                    icon.setImageResource(android.R.drawable.checkbox_on_background);
                    icon.setColorFilter(Color.parseColor("#39FF14"));
                    tv.setTextColor(Color.parseColor("#39FF14"));
                    break;
                case SUB_FAIL:
                    icon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                    icon.setColorFilter(Color.parseColor("#FF4444"));
                    tv.setTextColor(Color.parseColor("#FF4444"));
                    break;
                case SUB_RUNNING:
                    icon.setImageResource(android.R.drawable.presence_online);
                    icon.setColorFilter(Color.parseColor("#FFB020"));
                    tv.setTextColor(Color.parseColor("#FFB020"));
                    break;
                default:
                    icon.setImageResource(android.R.drawable.presence_invisible);
                    icon.setColorFilter(Color.parseColor("#7A8A80"));
                    tv.setTextColor(Color.parseColor("#7A8A80"));
                    break;
            }
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    /** 更新模块状态 + 进度。不覆盖子步骤状态（子步骤由 onSubStep 单独控制） */
    public void updateStatus(String title, int pct, String status) {
        for (int i = 0; i < items.size(); i++) {
            ModuleItem m = items.get(i);
            if (m.title.equals(title)) {
                m.status = status;
                m.progress = Math.max(0, Math.min(100, pct));
                // 模块通过/失败时，批量设置子步骤状态
                if (m.subStatuses != null) {
                    int subState = -1; // -1 表示不修改
                    if (status != null) {
                        if (status.contains("通过")) subState = SUB_PASS;
                        else if (status.contains("警告") || status.contains("未通过") || status.contains("❌")) subState = SUB_FAIL;
                    }
                    if (subState >= 0) {
                        for (int j = 0; j < m.subStatuses.length; j++) m.subStatuses[j] = subState;
                    }
                }
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** 更新单个子步骤状态 */
    public void updateSubStatus(String moduleTitle, int subIndex, int status) {
        for (int i = 0; i < items.size(); i++) {
            ModuleItem m = items.get(i);
            if (m.title.equals(moduleTitle) && m.subStatuses != null && subIndex < m.subStatuses.length) {
                m.subStatuses[subIndex] = status;
                notifyItemChanged(i);
                return;
            }
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivModuleIcon, ivExpand;
        TextView tvTitle, tvDesc, tvStatus;
        LinearLayout subContainer;
        android.widget.ProgressBar progressBar;
        // 缓存子项视图引用，避免高频 notifyItemChanged 时 removeAllViews 重建
        ImageView[] subIcons;
        TextView[] subTexts;

        VH(View v) {
            super(v);
            ivModuleIcon = v.findViewById(R.id.iv_module_icon);
            tvTitle = v.findViewById(R.id.tv_module_title);
            tvDesc = v.findViewById(R.id.tv_module_desc);
            tvStatus = v.findViewById(R.id.tv_module_status);
            subContainer = v.findViewById(R.id.sub_items_container);
            ivExpand = v.findViewById(R.id.iv_expand);
            progressBar = v.findViewById(R.id.module_progress);
        }
    }
}
