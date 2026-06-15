package com.getjobs.worker.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static com.getjobs.worker.utils.Constant.UNLIMITED_CODE;

@Slf4j
public class JobUtils {

    public static String appendParam(String name, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Optional.of(value)
                .filter(v -> !Objects.equals(UNLIMITED_CODE, v))
                .map(v -> "&" + name + "=" + v)
                .orElse("");
    }

    public static String appendListParam(String name, List<String> values) {
        // 需求：如果列表包含 0（UNLIMITED_CODE），表示该参数不设置，直接返回 null
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.stream().anyMatch(v -> Objects.equals(UNLIMITED_CODE, v))) {
            return "";
        }
        return "&" + name + "=" + String.join(",", values);
    }

    // getConfig(Class<T>) 方法已移除；配置改为由各平台服务通过数据库读取构建

    /**
     * 计算并格式化时间（毫秒）
     *
     * @param startDate 开始时间
     * @param endDate   结束时间
     * @return 格式化后的时间字符串，格式为 "HH:mm:ss"
     */
    public static String formatDuration(Date startDate, Date endDate) {
        long durationMillis = endDate.getTime() - startDate.getTime();
        long seconds = (durationMillis / 1000) % 60;
        long minutes = (durationMillis / (1000 * 60)) % 60;
        long hours = durationMillis / (1000 * 60 * 60);
        return String.format("%d时%d分%d秒", hours, minutes, seconds);
    }

    /**
     * 将给定的毫秒时间戳转换为格式化的时间字符串
     *
     * @param durationSeconds 持续时间的时间戳（秒）
     * @return 格式化后的时间字符串，格式为 "HH:mm:ss"
     */
    public static String formatDuration(long durationSeconds) {
        long seconds = durationSeconds % 60;
        long minutes = (durationSeconds / 60) % 60;
        long hours = durationSeconds / 3600; // 直接计算总小时数

        return String.format("%d时%d分%d秒", hours, minutes, seconds);
    }


    // 定时任务相关方法已移除：系统不再支持定时推送

    public static int getRandomNumberInRange(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("max must be greater than or equal to min");
        }
        Random random = new Random();
        return random.nextInt((max - min) + 1) + min;
    }

    /**
     * 判断是否为非技术类岗位（如销售、客服、人事等），防范技术岗位误投递
     *
     * @param jobTitle 岗位名称
     * @return 是否应被过滤
     */
    public static boolean isNonTechnicalJob(String jobTitle) {
        if (jobTitle == null || jobTitle.trim().isEmpty()) {
            return false;
        }
        String title = jobTitle.trim().toLowerCase();
        String[] blackKeywords = {
            "销售", "商务", "客服", "招聘", "人事", "前台", "电销", "面销", 
            "销售代表", "客户代表", "销售经理", "课程顾问", "求职顾问", "规划顾问", 
            "置业顾问", "销售专员", "商务专员"
        };
        for (String kw : blackKeywords) {
            if (title.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        Date star = new Date();
        PlaywrightUtil.sleep(3);
        String a = formatDuration(star, new Date());
        System.out.println(a);
    }
}

