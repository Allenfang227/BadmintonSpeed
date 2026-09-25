package com.badminton.speed.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.badminton.speed.R;
import com.badminton.speed.data.DetectResult;

import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    private final List<DetectResult> items;

    public HistoryAdapter(List<DetectResult> items) { this.items = items; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_history, p, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        DetectResult r = items.get(pos);
        h.tvDate.setText(r.formattedDate());
        h.tvInfo.setText(String.format("FPS:%d  击球:%s  %s",
                r.fps, r.hitType != null ? r.hitType : "-",
                r.inOut != null ? r.inOut : ""));
        h.tvMaxSpeed.setText(String.format("%.0f", r.maxSpeed));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvDate, tvInfo, tvMaxSpeed;
        VH(View v) {
            super(v);
            tvDate = v.findViewById(R.id.tv_date);
            tvInfo = v.findViewById(R.id.tv_info);
            tvMaxSpeed = v.findViewById(R.id.tv_max_speed);
        }
    }
}
