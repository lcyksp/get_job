package com.getjobs.worker.boss;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final AiService aiService;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 安全执行页面操作，当页面因反检测刷新导致 frame 失效时自动重试
     */
    @FunctionalInterface
    private interface PageAction<T> {
        T run() throws Exception;
    }

    private <T> T safePageAction(PageAction<T> action, String description) {
        return safePageAction(action, description, 3);
    }

    private <T> T safePageAction(PageAction<T> action, String description, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.run();
            } catch (Exception e) {
                lastException = e;
                if (e.getMessage() != null && e.getMessage().contains("Object doesn't exist")) {
                    log.warn("[Boss] 页面frame失效({})，第{}次重试...", description, attempt);
                    PlaywrightUtil.sleep(2);
                    try {
                        page.reload(new Page.ReloadOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(15_000));
                        PlaywrightUtil.sleep(2);
                    } catch (Exception reloadEx) {
                        log.debug("[Boss] 重载页面失败: {}", reloadEx.getMessage());
                    }
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new RuntimeException("[Boss] 页面操作重试" + maxRetries + "次后仍失败: " + description, lastException);
    }

    /**
     * 执行投递
     */
    public int execute() {
        for (String cityCode : config.getCityCode()) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
            postJobByCity(cityCode);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
        }
        return resultList.size();
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            final String finalCompanyName = companyName;
                            if (blackCompanies.stream().anyMatch(p -> finalCompanyName.toLowerCase().contains(p.toLowerCase()))) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            // 检查是否需要停止
            if (shouldStopCallback.get()) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            int postCount = 0;
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            final String navUrl = url;
            safePageAction(() -> {
                page.navigate(navUrl, new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(15_000));
                // 等待列表容器出现，确保页面完成首屏渲染
                page.waitForSelector("//ul[contains(@class, 'rec-job-list')]", new Page.WaitForSelectorOptions().setTimeout(60_000));
                return null;
            }, "导航到搜索页面: " + keyword);

            // 1. 基于 footer 出现滚动到底，确保加载全部岗位
            int lastCount = -1;
            int stableTries = 0;
            for (int i = 0; i < 5000; i++) { // 最多尝试约120次，避免死循环
                // 停止检查：滚动加载过程中也要及时响应
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", 0, 0);
                    return;
                }
                Locator footer = page.locator("div#footer, #footer");
                if (footer.count() > 0 && footer.first().isVisible()) {
                    break; // 到达页面底部
                }
                // 按视口高度的90%渐进滚动，触发懒加载
                page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");

                // 获取卡片数量变化，判断是否需要强制触底
                Locator cardsProbe = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                int currentCount = cardsProbe.count();
                if (currentCount == lastCount) {
                    stableTries++;
                } else {
                    stableTries = 0;
                }
                lastCount = currentCount;

                if (stableTries >= 3) { // 连续多次无新增，则强制触底一次
                    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    // 触底不再等待，继续检测 footer 出现
                }
            }
            // 统计最终岗位数量
            Locator cardsFinal = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            int loadedCount = cardsFinal.count();
            log.info("【{}】岗位已全部加载，总数:{}", keyword, loadedCount);
            progressCallback.accept("岗位加载完成：" + keyword, 0, loadedCount);

            // 2. 回到页面顶部
            page.evaluate("window.scrollTo(0, 0);");
            PlaywrightUtil.sleep(1);

            // 3. 逐个遍历所有岗位
            Locator cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            int count = cards.count();
            for (int i = 0; i < count; i++) {
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", i, count);
                    return;
                }

                // 重新载入页面后，确保 DOM 中已加载出至少 i + 1 个卡片（防止懒加载页面刷新后列表被重置变短）
                int scrollTries = 0;
                while (true) {
                    cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                    if (cards.count() > i || scrollTries >= 20) {
                        break;
                    }
                    page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.2))");
                    PlaywrightUtil.sleepMillis(500);
                    scrollTries++;
                }

                // 重新获取卡片，避免元素过期
                cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                try {
                    cards.nth(i).scrollIntoViewIfNeeded();
                    PlaywrightUtil.sleepMillis(500);
                } catch (Throwable ignore) {}
                // 在点击卡片时同步等待岗位详情接口返回，随后解析并入库
                final int idx = i;
                final Locator finalCards = cards;
                Response detailResp = safePageAction(() -> {
                    try {
                        if (idx == 0 && count > 1) {
                            final Locator secondCard = finalCards.nth(1);
                            secondCard.click();
                            PlaywrightUtil.sleep(1);
                            final Locator firstCard = finalCards.nth(0);
                            return page.waitForResponse(r -> {
                                try {
                                    return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                            && "GET".equalsIgnoreCase(r.request().method());
                                } catch (Throwable ignore) { return false; }
                            }, firstCard::click);
                        } else {
                            final Locator cardToClick = finalCards.nth(idx);
                            return page.waitForResponse(r -> {
                                try {
                                    return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                            && "GET".equalsIgnoreCase(r.request().method());
                                } catch (Throwable ignore) { return false; }
                            }, cardToClick::click);
                        }
                    } catch (Throwable e) {
                        return null;
                    }
                }, "点击岗位卡片 #" + i);
                PlaywrightUtil.sleep(1);

                // 统一从请求返回的 JSON 中获取数据并做过滤
                String jobName = null;
                String jobSalary = null;
                java.util.List<String> tags = new java.util.ArrayList<>();
                String jobDesc = null;
                String bossName = null;
                String bossActive = null;
                String bossCompany = null;
                String bossJobTitle = null;
                String securityId = null;
                String encryptJobId = null;

                if (detailResp != null) {
                    try {
                        String body = detailResp.text();
                        // 保存原始 JSON 便于调试
                        appendRawJson(body);
                        // 解析并入库（仅在点击卡片触发时执行）
                        processJobDetailJsonAndInsert(body);

                        // 从 JSON 构建用于投递与过滤的字段
                        org.json.JSONObject root = new org.json.JSONObject(body);
                        org.json.JSONObject zpData = root.optJSONObject("zpData");
                        org.json.JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
                        org.json.JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
                        org.json.JSONObject boss = zpData != null ? zpData.optJSONObject("bossInfo") : null;

                        if (zpData != null) {
                            securityId = zpData.optString("securityId", "");
                        }

                        if (jobInfo != null) {
                            jobName = jobInfo.optString("jobName", "");
                            jobSalary = jobInfo.optString("salaryDesc", "");
                            String city = jobInfo.optString("locationName", "");
                            String exp = jobInfo.optString("experienceName", "");
                            String deg = jobInfo.optString("degreeName", "");
                            if (!city.isEmpty()) tags.add(city);
                            if (!exp.isEmpty()) tags.add(exp);
                            if (!deg.isEmpty()) tags.add(deg);
                            jobDesc = jobInfo.optString("postDescription", "");
                            encryptJobId = jobInfo.optString("encryptId", "");
                        }

                        if (boss != null) {
                            bossName = boss.optString("name", "");
                            bossActive = boss.optString("activeTimeDesc", "");
                            bossJobTitle = boss.optString("title", "");
                        }

                        if (brand != null) {
                            bossCompany = brand.optString("brandName", "");
                        }
                    } catch (Throwable e) {
                        log.debug("点击卡片后解析岗位详情用于过滤失败：{}", e.getMessage());
                    }
                }

                // 过滤（全部基于 JSON 字段），并输出过滤原因
                final String finalJobName = jobName;
                final String finalBossCompany = bossCompany;
                final String finalBossJobTitle = bossJobTitle;
                final String finalJobDesc = jobDesc;
                
                boolean isJobBlacklisted = false;
                String matchedJobTerm = null;
                if (blackJobs != null) {
                    if (jobName != null) {
                        matchedJobTerm = findMatchedTerm(blackJobs, jobName);
                        if (matchedJobTerm != null) {
                            isJobBlacklisted = true;
                        }
                    }
                    if (!isJobBlacklisted && jobDesc != null) {
                        matchedJobTerm = findMatchedTerm(blackJobs, jobDesc);
                        if (matchedJobTerm != null) {
                            isJobBlacklisted = true;
                        }
                    }
                }
                
                if (isJobBlacklisted) {
                    log.info("被过滤：职位或JD黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName, matchedJobTerm != null ? matchedJobTerm : "");
                    continue;
                }
                // HR活跃状态过滤：当开启过滤开关且活跃描述包含“年”时，视为不活跃
                boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");
                if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
                    log.info("被过滤：HR活跃状态包含‘年’ | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
                    continue;
                }
                if (bossCompany != null && blackCompanies != null && blackCompanies.stream().anyMatch(p -> finalBossCompany.toLowerCase().contains(p.toLowerCase()))) {
                    String term = findMatchedTerm(blackCompanies, bossCompany);
                    log.info("被过滤：公司黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany, jobName != null ? jobName : "", term != null ? term : "");
                    continue;
                }
                if (bossJobTitle != null && blackRecruiters != null && blackRecruiters.stream().anyMatch(p -> finalBossJobTitle.toLowerCase().contains(p.toLowerCase()))) {
                    String term = findMatchedTerm(blackRecruiters, bossJobTitle);
                    log.info("被过滤：招聘者黑名单命中 | 公司：{} | 岗位：{} | 招聘者：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossJobTitle, term != null ? term : "");
                    continue;
                }

                // 创建Job对象（全部基于 JSON 字段）
                Job job = new Job();
                job.setJobName(jobName != null ? jobName : "");
                job.setSalary(jobSalary != null ? jobSalary : "");
                job.setJobArea(String.join(", ", tags));
                job.setCompanyName(bossCompany != null ? bossCompany : "");
                job.setRecruiter(bossName != null ? bossName : "");
                job.setJobInfo(jobDesc != null ? jobDesc : "");
                job.setSecurityId(securityId != null ? securityId : "");
                job.setEncryptJobId(encryptJobId != null ? encryptJobId : "");

                // 输出
                progressCallback.accept("正在投递：" + jobName, i + 1, count);
                String currentSearchUrl = page.url();
                resumeSubmission(keyword, job, currentSearchUrl);
                postCount++;

                // 为避免点击下面的卡片触发页面刷新：在点击5个卡片之后，每次点击后适度下滑
                try {
                    if (i >= 5) {
                        page.evaluate("window.scrollBy(0, 140);");
                        PlaywrightUtil.sleep(1);
                    }
                } catch (Throwable ignore) {}
            }
            log.info("【{}】岗位已投递完毕！已投递岗位数量:{}", keyword, postCount);
        }
    }

    /**
     * 解析岗位详情 JSON 并进行入库与黑名单处理（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = false;
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            String jobDescription = entity.getJobDescription() != null ? entity.getJobDescription() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(p -> companyName.toLowerCase().contains(p.toLowerCase()))) filtered = true;
                if (!filtered && blackJobs != null && (blackJobs.stream().anyMatch(p -> positionName.toLowerCase().contains(p.toLowerCase())) || blackJobs.stream().anyMatch(p -> jobDescription.toLowerCase().contains(p.toLowerCase())))) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(p -> hrPosition.toLowerCase().contains(p.toLowerCase()))) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            String lowerText = text.toLowerCase();
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && lowerText.contains(p.toLowerCase())) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    @SneakyThrows
    private void resumeSubmission(String keyword, Job job, String searchUrl) {
        // 实时加载最新的配置（保证运行时中途修改配置能即时生效）
        try {
            BossConfig latestConfig = bossService.loadBossConfig();
            if (latestConfig != null) {
                this.config = latestConfig;
            }
        } catch (Exception e) {
            log.warn("实时拉取最新Boss配置异常：{}", e.getMessage());
        }

        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }

        // 过滤非技术岗位（如销售、商务、客服等）
        if (com.getjobs.worker.utils.JobUtils.isNonTechnicalJob(job.getJobName())) {
            log.info("[Boss直聘] 过滤非技术岗（销售/客服/商务等）| 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }

        String securityId = job.getSecurityId();
        String jobId = job.getEncryptJobId();

        if (securityId == null || securityId.isEmpty() || jobId == null || jobId.isEmpty()) {
            log.warn("岗位 {} 缺少 securityId 或 jobId，无法进行 API 快速投递", job.getJobName());
            return;
        }

        // 查重：若该岗位或该公司已经成功投递过，则跳过
        if (bossService.isJobOrCompanyDelivered(jobId, job.getCompanyName())) {
            log.info("[Boss直聘] 跳过已投递过的岗位/公司 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }

        // 生成 AI 消息或使用默认招呼语
        String followUpMessage = "";
        if (Boolean.TRUE.equals(config.getEnableAI())) {
            String jd = job.getJobInfo();
            if (jd != null && !jd.isEmpty()) {
                log.info("🤖 正在为岗位 [{}] 使用 AI 生成个性化招呼语...", job.getJobName());
                followUpMessage = generateAiMessage(keyword, job.getJobName(), jd);
                log.info("🤖 AI 招呼语生成成功: {}", followUpMessage);
            }
        } else {
            // 未开启 AI 时，直接读取 AI 配置里面的“技能介绍”全文，原封不动地作为详细介绍发送
            try {
                com.getjobs.application.entity.AiEntity aiConfig = aiService.getAiConfig();
                if (aiConfig != null && aiConfig.getIntroduce() != null && !aiConfig.getIntroduce().trim().isEmpty()) {
                    followUpMessage = aiConfig.getIntroduce();
                    log.info("📝 未开启 AI 自动招呼语，直接使用 AI 配置中的【技能介绍】全文进行追发");
                } else {
                    followUpMessage = config.getSayHi();
                }
            } catch (Exception e) {
                log.warn("获取技能介绍全文异常，回退使用打招呼的语句：{}", e.getMessage());
                followUpMessage = config.getSayHi();
            }
        }

        log.info("🚀 正在通过 API 直投岗位: {} (公司: {})", job.getJobName(), job.getCompanyName());

        // 执行 JS 发送 AJAX 请求直投 (只发起沟通，详情保存在 Page Context)
        String js = "async (args) => {\n" +
                "  const secId = args[0];\n" +
                "  const jId = args[1];\n" +
                "  function getCookie(name) {\n" +
                "    const value = '; ' + document.cookie;\n" +
                "    const parts = value.split('; ' + name + '=');\n" +
                "    if (parts.length === 2) return parts.pop().split(';').shift();\n" +
                "  }\n" +
                "  const token = getCookie('bst');\n" +
                "  if (!token) {\n" +
                "    return { code: -1, message: 'Missing bst token' };\n" +
                "  }\n" +
                "  const url = `https://www.zhipin.com/wapi/zpgeek/friend/add.json?securityId=${secId}&jobId=${jId}`;\n" +
                "  try {\n" +
                "    const response = await fetch(url, {\n" +
                "      method: 'POST',\n" +
                "      headers: {\n" +
                "        'Zp_token': token,\n" +
                "        'accept': 'application/json, text/plain, */*'\n" +
                "      }\n" +
                "    });\n" +
                "    const json = await response.json();\n" +
                "    return json;\n" +
                "  } catch (e) {\n" +
                "    return { code: -2, message: e.message };\n" +
                "  }\n" +
                "}";

        try {
            Object resultObj = page.evaluate(js, java.util.List.of(
                    securityId != null ? securityId : "",
                    jobId != null ? jobId : ""
            ));
            if (resultObj instanceof java.util.Map) {
                java.util.Map<String, Object> resultMap = (java.util.Map<String, Object>) resultObj;
                Number codeNum = (Number) resultMap.get("code");
                int code = codeNum != null ? codeNum.intValue() : -1;
                String message = (String) resultMap.get("message");
                
                if (code == 0) {
                    log.info("✓ API 直投成功 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                    
                    // 获取 encryptUserId 并更新状态
                    String encryptId = jobId;
                    String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
                    if (encryptId != null && !encryptId.isEmpty() && encryptUserId != null && !encryptUserId.isEmpty()) {
                        try {
                            bossService.updateDeliveryStatus(encryptId, encryptUserId, "已投递");
                        } catch (Exception e) {
                            log.warn("更新投递状态为已投递失败：{}", e.getMessage());
                        }
                    }
                    resultList.add(job);

                    // 💬 直投成功后，自动前往聊天页补发详细的个人自我介绍
                    if (followUpMessage != null && !followUpMessage.trim().isEmpty()) {
                        sendFollowUpMessageInChat(followUpMessage, job.getRecruiter(), searchUrl);
                    }
                } else {
                    log.warn("✗ API 投递失败 | 公司：{} | 岗位：{} | 错误码: {} | 原因: {}", 
                            job.getCompanyName(), job.getJobName(), code, message);
                    
                    // 如果发生发送失败，进行数据库状态更新
                    String encryptId = jobId;
                    String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
                    if (encryptId != null && !encryptId.isEmpty() && encryptUserId != null && !encryptUserId.isEmpty()) {
                        try {
                            bossService.updateDeliveryStatus(encryptId, encryptUserId, "投递失败");
                        } catch (Exception e) {
                            log.warn("更新投递状态为投递失败异常：{}", e.getMessage());
                        }
                    }
                    
                    if (message != null && (message.contains("120") || message.contains("150") || message.contains("限制"))) {
                        log.error("⚠️ 已触发 Boss 投递上限提示: {}", message);
                    }
                }
            } else {
                log.error("API 投递返回值解析失败！");
            }
        } catch (Exception e) {
            log.error("API 投递执行异常：{}", e.getMessage());
        }

        // 适当休眠 3 ~ 5 秒，模拟人际投递间隔时间
        PlaywrightUtil.sleep(3 + new java.util.Random().nextInt(3));
    }

    /**
     * 前往聊天窗口，补发 AI 定制招呼语或预设技能介绍
     */
    private void sendFollowUpMessageInChat(String message, String hrName, String searchUrl) {
        log.info("💬 正在前往聊天页面为 HR [{}] 补发自我介绍...", hrName);
        try {
            // 1. 导航到聊天页面并等待 DOM 就绪
            page.navigate("https://www.zhipin.com/web/geek/chat", new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            PlaywrightUtil.sleepMillis(2500); // 等待聊天列表和 WebSocket 握手加载完成

            // 2. 等待聊天列表加载并获取最新会话项
            page.waitForSelector("//li[@role='listitem']", new Page.WaitForSelectorOptions().setTimeout(15_000));
            
            // 循环等待，直到最新第一项 of HR 名字或者公司名字与当前投递的 HR 匹配（或者超时兜底）
            Locator firstChatItem = null;
            boolean matched = false;
            for (int attempt = 0; attempt < 5; attempt++) {
                firstChatItem = page.locator("//li[@role='listitem']").first();
                if (firstChatItem.count() > 0) {
                    String itemText = firstChatItem.textContent();
                    if (itemText != null && hrName != null && itemText.contains(hrName)) {
                        matched = true;
                        log.info("🎯 成功匹配到刚建立的 HR 会话 [{}]", hrName);
                        break;
                    }
                }
                PlaywrightUtil.sleep(1); // 等待1秒让新会话同步到列表最上方
            }

            if (firstChatItem != null && firstChatItem.count() > 0) {
                if (!matched) {
                    log.warn("⚠️ 最新会话文本未匹配到 HR 名字 [{}], 将兜底使用列表首项发送", hrName);
                }
                firstChatItem.click();
                PlaywrightUtil.sleepMillis(1200);

                // 3. 定位聊天输入框（兼容多种选择器）
                Locator chatInput = page.locator("//div[@id='chat-input'] | //div[@contenteditable='true']").first();
                if (chatInput.count() > 0 && chatInput.isVisible()) {
                    chatInput.focus();
                    PlaywrightUtil.sleepMillis(300);
                    
                    // 模拟真实键盘操作：清空已有内容
                    page.keyboard().press("Control+A");
                    page.keyboard().press("Backspace");
                    PlaywrightUtil.sleepMillis(300);
                    
                    // 用 keyboard().type() 输入文本，100% 触发任何编辑器的键盘事件与数据流绑定
                    page.keyboard().type(message);
                    PlaywrightUtil.sleepMillis(800);
                    
                    // 4. 定位并点击发送按钮（兼容多种选择器，若无则敲击 Enter 键兜底）
                    Locator sendBtn = page.locator("//button[contains(@class, 'btn-send') or @type='send' or contains(text(), '发送')]").first();
                    if (sendBtn.count() > 0 && sendBtn.isVisible()) {
                        sendBtn.click();
                        log.info("✓ 详细介绍已通过‘发送’按钮发送给 [{}]", hrName);
                    } else {
                        page.keyboard().press("Enter");
                        log.info("✓ 详细介绍已通过 Enter 键发送给 [{}]", hrName);
                    }
                    PlaywrightUtil.sleep(2);
                } else {
                    log.warn("未找到聊天输入框，无法补发自我介绍");
                }
            } else {
                log.warn("聊天列表为空，未发现新建会话");
            }
        } catch (Exception e) {
            log.error("前往聊天页补发自我介绍异常：{}", e.getMessage());
        } finally {
            // 5. 无论发送成功或遇到异常，均安全导航回原岗位搜索页继续当前任务
            try {
                log.info("🔙 正在返回岗位搜索列表继续投递任务...");
                page.navigate(searchUrl, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(30_000));
                // 等待列表容器重新出现，保证后续遍历卡片时不会失效
                page.waitForSelector("//ul[contains(@class, 'rec-job-list')]", new Page.WaitForSelectorOptions().setTimeout(30_000));
                PlaywrightUtil.sleep(2);
            } catch (Exception reloadEx) {
                log.error("返回搜索列表页面失败：{}", reloadEx.getMessage());
            }
        }
    }

    

    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean sendImageResume(Page page) {
        try {
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = Boss.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                chatBtn.first().click();
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    boolean chooserHandled = false;
                    try {
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            imgContainer.first().click();
                        });
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException ignore) {
                        // 未弹出系统文件选择器，继续常规流程
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            imageInput.setInputFiles(imagePath);
            PlaywrightUtil.sleep(1);
            return true;
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = Boss.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = Boss.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private String generateAiMessage(String keyword, String jobName, String jd) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

        String requestMessage;
        if (prompt != null && !prompt.trim().isEmpty() && prompt.contains("%s") && !prompt.contains("请在此填写")) {
            try {
                requestMessage = String.format(prompt, introduce, keyword, jobName, jd, config.getSayHi());
                // 强力后置红线指令，对自定义 Prompt 实施兜底防御，强制锁死身份与真实技能，严禁虚构任何年限！
                requestMessage += "\n\n【绝对红线约束 - 必须严格遵守】：\n" +
                        "1. 严禁虚构任何个人技能介绍中未提及的工作年限、项目经验、学历。如果其中写明是“在校生”、“26年毕业”、“寻找实习”、“应届”，必须严格以真实学生/实习生身份打招呼，绝对禁止虚构、编造或暗示任何“有工作年限”、“工作X年”等虚假信息！\n" +
                        "2. 严禁夸大与捏造技能，只从给定的介绍中提炼，不允许无中生有！\n" +
                        "3. 字数严格控制在 50 字以内，直接输出打招呼正文，不需要任何旁白解释或标点引号。";
            } catch (Exception e) {
                log.warn("自定义AI Prompt格式化失败，退回到默认Prompt：{}", e.getMessage());
                requestMessage = buildDefaultPrompt(introduce, keyword, jobName, jd);
            }
        } else {
            requestMessage = buildDefaultPrompt(introduce, keyword, jobName, jd);
        }

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null) {
                return config.getSayHi();
            }
            return result.toLowerCase().contains("false") ? config.getSayHi() : result;
        } catch (Exception e) {
            log.warn("AI请求失败，使用原有打招呼语: {}", e.getMessage());
            return config.getSayHi();
        }
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd) {
        return "【核心指令】\n" +
                "请将【个人技能介绍】提炼为一段发给 HR 的简短求职打招呼语。字数严格控制在 50 字以内。\n\n" +
                "【绝对红线 - 违背将产生严重后果】\n" +
                "1. 严禁编造任何经历：禁止虚构任何个人介绍中没有提及的工作年限、项目经验、学历。如果介绍中写明是“在校生”、“26年毕业”、“应届”、“寻找实习”，必须严格以学生/实习生的在校生身份问候，绝对禁止出现“工作X年”、“有X年经验”等词汇！\n" +
                "2. 严禁夸大与添加：只提炼个人介绍中明确写出的技术（如小程序开发、App全栈、AI Agent、docker容器化部署等），凡是个人介绍里没有的词，一律不准无中生有地添加或夸大！\n" +
                "3. 保持身份一致：必须使用谦逊、诚恳的在校生语气，禁止一切过度“包装”或合理化。\n" +
                "4. 绝不胡扯：如果无法结合 JD 做提炼，就直接原样概括个人技能介绍即可。\n\n" +
                "【输入数据】\n" +
                "个人技能介绍：" + introduce + "\n" +
                "求职参考信：" + config.getSayHi() + "\n" +
                "目标岗位名：" + jobName + "\n" +
                "目标岗位JD：" + jd + "\n\n" +
                "【输出格式】\n" +
                "只输出打招呼正文，不需要任何前言、旁白或引号。";
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    private void waitForSliderVerify(Page page) {
        String SLIDER_URL = "https://www.zhipin.com/web/user/safe/verify-slider";
        // 最多等待5分钟（防呆，防止死循环）
        long start = System.currentTimeMillis();
        while (true) {
            String url = page.url();
            if (url != null && url.startsWith(SLIDER_URL)) {
                progressCallback.accept("请手动完成Boss直聘滑块验证，通过后在控制台回车继续...", 0, 0);
                System.out.println("\n【滑块验证】请手动完成Boss直聘滑块验证，通过后在控制台回车继续…");
                try {
                    System.in.read();
                } catch (Exception e) {
                    log.error("等待滑块验证输入异常: {}", e.getMessage());
                }
                PlaywrightUtil.sleep(1);
                // 验证通过后页面url会变，循环再检测一次
                continue;
            }
            if ((System.currentTimeMillis() - start) > 5 * 60 * 1000) {
                throw new RuntimeException("滑块验证超时！");
            }
            break;
        }
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    errorLoginLocator.click();
                }
                return true;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
    }

}
