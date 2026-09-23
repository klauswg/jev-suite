package com.jevsuite.rental.service;

import com.jevsuite.kit.client.Answer;
import com.jevsuite.kit.client.JevClient;
import com.jevsuite.kit.client.JevResponse;
import com.jevsuite.kit.client.TypeSafeJevClient.JevUnavailableException;
import com.jevsuite.kit.state.ExternalStringSanitizer;
import com.jevsuite.rental.domain.ChecklistReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 看房确认清单主链路（jev-rental PRD §3）：
 * 分句 → 每声明一次 Jev 三问（Noul 是否可核验声明 + Choice 三类 + Choice 动线类目）
 * → 门控（noul < 0.5 进 fluff；choice 信度 < 0.70 降级为「现场确认」最保守桶）
 * → 高风险归因（二次调用，成本上限 5）→ 租金硬规则 → 动线排序。
 * 定位红线：输出「该问什么」，永不输出「房源真不真」；质询话术用代码模板，不让模型生成。
 */
@Service
public class RentalService {

    private static final Logger log = LoggerFactory.getLogger(RentalService.class);
    private static final double CHOICE_GATE = 0.70;
    private static final int RISK_REASON_COST_CAP = 5;
    private static final int MIN_TEXT_LEN = 20;

    private final JevClient jev;
    private final ExternalStringSanitizer san = new ExternalStringSanitizer();

    public RentalService(JevClient jev) {
        this.jev = jev;
    }

    public ChecklistReport analyze(String text, String city, Integer rent) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<ListingParser.Claim> claims = ListingParser.parse(text);
        if (text == null || text.trim().length() < MIN_TEXT_LEN || claims.isEmpty()) {
            return new ChecklistReport(runId, "INSUFFICIENT_INFO", city, rent, null,
                    insufficientInfoChecklist(), List.of(), 0, false);
        }

        boolean degraded = false;
        long tokens = 0;
        int riskReasonCalls = 0;
        List<ChecklistReport.CheckItem> items = new ArrayList<>();
        List<String> fluff = new ArrayList<>();

        for (ListingParser.Claim c : claims) {
            String safe = san.text(c.text(), 300);
            Map<String, Object> q = Map.of(
                    "is_claim", Map.of("type", "noul",
                            "instructions", "This sentence is a concrete, checkable claim about the rental "
                                    + "property (price, fees, facilities, location, lease terms) OR a marketing "
                                    + "pressure / urgency / bait statement aimed at the renter — both are in scope. "
                                    + "Only pure emotional copy with no verifiable content is not a claim."),
                    "claim_class", Map.of("type", "choice",
                            "instructions", "How should a renter verify this claim before signing?",
                            "criteria", Map.of(
                                    "on_site_verifiable", "can be checked in person during the viewing "
                                            + "(facilities, lighting, elevator, actual condition)",
                                    "need_evidence", "requires documents from the agent/landlord "
                                            + "(ownership, no-agency-fee, utility billing type, deposit terms)",
                                    "high_risk_pitch", "marketing pressure or bait language "
                                            + "(urgency, below-market price, vague disclaimer)",
                                    "fluff", "not a checkable claim")),
                    "category", Map.of("type", "choice",
                            "instructions", "Which checklist section does this claim belong to?",
                            "criteria", Map.of(
                                    "property_rights", "ownership, landlord identity, sublease legality",
                                    "hardware", "facilities, appliances, structure, lighting, noise",
                                    "fees", "rent, deposit, agency fee, utilities, other charges",
                                    "contract", "lease terms, duration, penalty, renewal",
                                    "other", "none of the above")));
            try {
                JevResponse r0 = jev.evaluate(renderState(city, rent, safe), q);
                tokens += r0.inputTokens();
                Answer.NoulAnswer isClaim = (Answer.NoulAnswer) r0.answers().get("is_claim");
                Answer.ChoiceAnswer cls = (Answer.ChoiceAnswer) r0.answers().get("claim_class");
                Answer.ChoiceAnswer cat = (Answer.ChoiceAnswer) r0.answers().get("category");

                if (isClaim.value() < 0.5 || "fluff".equals(cls.choice())) {
                    fluff.add(safe);
                    continue;
                }

                double conf = cls.confidence() != null ? cls.confidence() : 0.0;
                String rawClass = switch (cls.choice()) {
                    case "on_site_verifiable" -> "ON_SITE";
                    case "need_evidence" -> "NEED_EVIDENCE";
                    case "high_risk_pitch" -> "HIGH_RISK";
                    default -> "ON_SITE";   // 未知类 → 最保守桶
                };
                // 低信度降级：疑似风险话术降 NEED_EVIDENCE（要求书面确认），其余降 ON_SITE——绝不丢弃
                String claimClass = conf < CHOICE_GATE
                        ? ("HIGH_RISK".equals(rawClass) ? "NEED_EVIDENCE" : "ON_SITE") : rawClass;

                String riskReason = null;
                if ("HIGH_RISK".equals(claimClass) && conf >= CHOICE_GATE
                        && riskReasonCalls < RISK_REASON_COST_CAP) {
                    riskReasonCalls++;
                    try {
                        JevResponse r1 = jev.evaluate("Rental listing claim flagged as high-risk pitch.\n"
                                        + "claim: " + safe + "\n",
                                Map.of("risk_reason", Map.of("type", "choice",
                                        "instructions", "What is the main risk pattern of this claim?",
                                        "criteria", Map.of(
                                                "low_price_bait", "price or terms too good, likely bait",
                                                "vague_disclaimer", "vague wording that dodges responsibility",
                                                "sublease_risk", "possible unauthorized sublet / second landlord",
                                                "deposit_trap", "deposit or fee collection risk"))));
                        tokens += r1.inputTokens();
                        riskReason = ((Answer.ChoiceAnswer) r1.answers().get("risk_reason")).choice();
                    } catch (JevUnavailableException e) {
                        degraded = true;
                    }
                }

                items.add(new ChecklistReport.CheckItem(c.no(), safe, claimClass,
                        cat.choice(), riskReason, rawClass, conf, questionFor(claimClass, riskReason, safe)));
            } catch (JevUnavailableException e) {
                degraded = true;
                // 模型不可达 → 该声明进最保守桶，绝不丢弃（PRD §3）
                items.add(new ChecklistReport.CheckItem(c.no(), safe, "ON_SITE", "other",
                        null, "ON_SITE", -1, questionFor("ON_SITE", null, safe)));
            }
        }

        // 硬规则：租金偏离内置区间 ±40% → 固定挂低价引流嫌疑（不调 Jev）
        Boolean rentOutlier = null;
        items.sort(Comparator.comparingInt(i -> categoryOrder(i.category())));
        if (rent != null && rent > 0) {
            rentOutlier = RentRanges.isOutlier(city, rent);
            if (rentOutlier) {
                items.add(0, new ChecklistReport.CheckItem(0,
                        "挂牌租金 " + rent + " 元/月，偏离 " + (city != null ? city : "该城市")
                                + " 内置区间（" + RentRanges.describe(city) + "）±40%",
                        "HIGH_RISK", "fees", "low_price_bait", "HIGH_RISK", 1.0,
                        questionFor("HIGH_RISK", "low_price_bait", null)));
            }
        }
        String status = (degraded || items.stream().anyMatch(i -> i.confidence() < 0))
                ? "NEEDS_HUMAN_REVIEW" : "READY";
        return new ChecklistReport(runId, status, city, rent, rentOutlier,
                List.copyOf(items), List.copyOf(fluff), tokens, degraded);
    }

    private String renderState(String city, Integer rent, String claim) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("Rental listing claim check.\n");
        if (city != null && !city.isBlank()) sb.append("city: ").append(san.text(city, 30)).append('\n');
        if (rent != null) sb.append("listed_rent_cny_month: ").append(rent).append('\n');
        sb.append("claim: ").append(claim).append('\n');
        return sb.toString();
    }

    /** 质询话术：代码模板（可审计），不让模型自由生成。 */
    static String questionFor(String claimClass, String riskReason, String claim) {
        return switch (claimClass) {
            case "NEED_EVIDENCE" -> "请提供该声明的证明材料（房产证/缴费单/合同条款等），并在合同中注明。";
            case "HIGH_RISK" -> switch (riskReason != null ? riskReason : "") {
                case "low_price_bait" -> "该条件明显优于同区域水平，请问是否存在额外费用、房源是否仍在？请提供实拍视频。";
                case "vague_disclaimer" -> "请将该模糊表述写进合同具体条款，明确责任方与金额。";
                case "sublease_risk" -> "请出示房产证与房东身份证，确认出租人有权出租；如是转租请出示原合同转租条款。";
                case "deposit_trap" -> "请明确押金金额、退还条件与退还时限，并写入合同。";
                default -> "该声明存在风险话术特征，请要求对方书面确认具体含义。";
            };
            default -> "看房时请现场确认该项，与描述不符处拍照留证。";
        };
    }

    static int categoryOrder(String category) {
        return switch (category != null ? category : "other") {
            case "property_rights" -> 0;
            case "hardware" -> 1;
            case "fees" -> 2;
            case "contract" -> 3;
            default -> 4;
        };
    }

    /** 描述过短时的固定索证清单（PRD §3：信息量不足 → 直接索证）。 */
    private List<ChecklistReport.CheckItem> insufficientInfoChecklist() {
        return List.of(
                new ChecklistReport.CheckItem(1, "描述信息量不足", "NEED_EVIDENCE", "property_rights",
                        null, "NEED_EVIDENCE", 1.0, "请提供房产证与房东身份证明，确认产权与出租权。"),
                new ChecklistReport.CheckItem(2, "描述信息量不足", "NEED_EVIDENCE", "fees",
                        null, "NEED_EVIDENCE", 1.0, "请书面列明全部费用：租金、押金、中介费、水电燃、物业费、网费。"),
                new ChecklistReport.CheckItem(3, "描述信息量不足", "NEED_EVIDENCE", "contract",
                        null, "NEED_EVIDENCE", 1.0, "请提供合同模板，确认租期、违约条款、押金退还条件。"));
    }

    public boolean isMockMode() { return jev.isMock(); }
}
