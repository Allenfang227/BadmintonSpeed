package com.badminton.speed.data;

import java.io.Serializable;

/**
 * 单次测速结果数据模型。
 */
public class DetectResult implements Serializable {
    public long id;
    public long timestamp;          // 结果生成时间
    public String videoPath;        // 源视频路径
    public int fps;                 // 源视频 FPS
    public int width, height;       // 源视频分辨率

    // 模块状态
    public boolean courtOK;         // 场地检测通过
    public boolean shuttleOK;       // 羽毛球检测通过
    public boolean playerOK;        // 人员检测通过
    public boolean hitOK;           // 击球点检测通过
    public boolean speedOK;         // 速度计算通过

    // 结果
    public double maxSpeed;         // 最高球速 km/h
    public double avgSpeed;         // 平均球速 km/h
    public String hitType;          // 击球类型（杀球/高远/吊球/...）
    public String inOut;            // 界内 / 界外
    public String shotSpeed;        // 出拍速度
    public String realtimeSpeed;    // 实时速度

    // 轨迹（序列化存储用，简化为 JSON 字符串）
    public String trajectoryJson;

    // 校验错误列表
    public String errors;

    public DetectResult() {
        this.timestamp = System.currentTimeMillis();
    }

    public String formattedDate() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
        return sdf.format(new java.util.Date(timestamp));
    }
}
