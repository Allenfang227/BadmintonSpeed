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

import java.util.Arrays;
import java.util.List;

/**
 * 右侧算法模块状态卡片的 RecyclerView Adapter。
 */
public class AlgoModuleAdapter extends RecyclerView.Adapter<AlgoModuleAdapter.VH> {

    public static class ModuleItem {
        public String title;
        public String desc;
        public String[] subItems;
        public String status;       // "未检测" / "检测中 · 22%" / "通过 ✅" / "未通过 ❌" / "警告⚠"
        public boolean expanded;
    }

    public final List<ModuleItem> items;

    public AlgoModuleAdapter(List<ModuleItem> items) {
        this.items = items;
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
        h.tvTitle.setText(m.title);
        h.tvDesc.setText(m.desc);
        h.tvStatus.setText(m.status);
        if (m.subItems != null && m.subItems.length > 0) {
            h.tvSubItems.setText(String.join("\n", m.subItems));
        }

        // 子项展开
        h.subContainer.setVisibility(m.expanded ? View.VISIBLE : View.GONE);
        h.ivExpand.setRotation(m.expanded ? 0 : 180);
        h.itemView.setOnClickListener(v -> {
            m.expanded = !m.expanded;
            notifyItemChanged(pos);
        });

        // 状态着色
        String s = m.status;
        if (s.contains("通过") || s.contains("Done") || s.contains("✅")) {
            h.tvStatus.setTextColor(Color.parseColor("#39FF14"));
        } else if (s.contains("未通过") || s.contains("❌")) {
            h.tvStatus.setTextColor(Color.parseColor("#FF4444"));
        } else if (s.contains("警告") || s.contains("⚠")) {
            h.tvStatus.setTextColor(Color.parseColor("#FFB020"));
        } else if (s.contains("检测中")) {
            h.tvStatus.setTextColor(Color.parseColor("#FFB020"));
        } else {
            h.tvStatus.setTextColor(Color.parseColor("#7A8A80"));
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    public void updateStatus(String title, String status) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).title.equals(title)) {
                items.get(i).status = status;
                notifyItemChanged(i);
                return;
            }
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivModuleIcon, ivExpand;
        TextView tvTitle, tvDesc, tvStatus, tvSubItems;
        LinearLayout subContainer;

        VH(View v) {
            super(v);
            ivModuleIcon = v.findViewById(R.id.iv_module_icon);
            tvTitle = v.findViewById(R.id.tv_module_title);
            tvDesc = v.findViewById(R.id.tv_module_desc);
            tvStatus = v.findViewById(R.id.tv_module_status);
            tvSubItems = v.findViewById(R.id.sub_items);
            subContainer = v.findViewById(R.id.sub_items_container);
            ivExpand = v.findViewById(R.id.iv_expand);
        }
    }
}
