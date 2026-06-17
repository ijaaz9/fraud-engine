package com.fraud.detection.api;
import com.fraud.detection.api.controller.FraudFlagController;
import com.fraud.detection.api.dto.FraudFlagDto.FlagEntry;
import com.fraud.detection.api.dto.FraudFlagDto.FlagSummary;
import com.fraud.detection.api.dto.FraudFlagDto.FraudStatsResponse;
import com.fraud.detection.api.dto.FraudFlagDto.PagedFlagResponse;
import com.fraud.detection.api.dto.FraudFlagDto.TransactionFlagDetail;
import com.fraud.detection.exception.GlobalExceptionHandler;
import com.fraud.detection.exception.TransactionNotFoundException;
import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.service.FraudFlagQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@ExtendWith(MockitoExtension.class)
@DisplayName("FraudFlagController")
class FraudFlagControllerTest {
    @Mock
    private FraudFlagQueryService queryService;
    private MockMvc mockMvc;
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FraudFlagController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
    // -------------------------------------------------------------------------
    // GET /api/v1/flags
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET /api/v1/flags — returns 200 with paginated results")
    void getFlags_returnsPagedResults() throws Exception {
        PagedFlagResponse response = PagedFlagResponse.builder()
                .content(List.of(buildFlagSummary()))
                .page(0)
                .size(20)
                .totalElements(1L)
                .totalPages(1)
                .last(true)
                .build();
        when(queryService.getFlags(isNull(), eq(0), eq(20))).thenReturn(response);
        mockMvc.perform(get("/api/v1/flags")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value("txn-001"))
                .andExpect(jsonPath("$.content[0].severity").value("HIGH"));
    }
    @Test
    @DisplayName("GET /api/v1/flags?status=OPEN — filters by status")
    void getFlags_withStatusFilter_passesStatusToService() throws Exception {
        PagedFlagResponse response = PagedFlagResponse.builder()
                .content(List.of())
                .page(0).size(20).totalElements(0L).totalPages(0).last(true)
                .build();
        when(queryService.getFlags(eq(FlagStatus.OPEN), eq(0), eq(20))).thenReturn(response);
        mockMvc.perform(get("/api/v1/flags?status=OPEN")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test
    @DisplayName("GET /api/v1/flags — enforces max page size of 100")
    void getFlags_exceedingMaxPageSize_capsAtHundred() throws Exception {
        PagedFlagResponse response = PagedFlagResponse.builder()
                .content(List.of()).page(0).size(100).totalElements(0L).totalPages(0).last(true)
                .build();
        when(queryService.getFlags(isNull(), eq(0), eq(100))).thenReturn(response);
        mockMvc.perform(get("/api/v1/flags?size=999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
    // -------------------------------------------------------------------------
    // GET /api/v1/flags/transactions/{transactionId}/flags
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("returns 200 with flag detail for a transaction")
    void getFlagsForTransaction_returnsDetail() throws Exception {
        TransactionFlagDetail detail = buildTransactionFlagDetail();
        when(queryService.getFlagsForTransaction("txn-001")).thenReturn(detail);
        mockMvc.perform(get("/api/v1/flags/transactions/txn-001/flags")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-001"))
                .andExpect(jsonPath("$.overallSeverity").value("HIGH"))
                .andExpect(jsonPath("$.flags").isArray())
                .andExpect(jsonPath("$.flags[0].ruleTriggered").value("HIGH_AMOUNT"));
    }
    @Test
    @DisplayName("returns 404 when transaction not found")
    void getFlagsForTransaction_notFound_returns404() throws Exception {
        when(queryService.getFlagsForTransaction("txn-unknown"))
                .thenThrow(new TransactionNotFoundException("txn-unknown"));
        mockMvc.perform(get("/api/v1/flags/transactions/txn-unknown/flags")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Transaction [txn-unknown] not found"));
    }
    // -------------------------------------------------------------------------
    // GET /api/v1/flags/stats
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET /api/v1/flags/stats — returns 200 with stats")
    void getStats_returnsAggregation() throws Exception {
        FraudStatsResponse stats = FraudStatsResponse.builder()
                .from(Instant.parse("2024-03-01T00:00:00Z"))
                .to(Instant.parse("2024-03-31T23:59:59Z"))
                .category("ELECTRONICS")
                .totalFlags(42L)
                .byRule(Map.of("HIGH_AMOUNT", 20L, "VELOCITY_CHECK", 22L))
                .bySeverity(Map.of("HIGH", 30L, "CRITICAL", 12L))
                .build();
        when(queryService.getStats(any(), any(), eq("ELECTRONICS"))).thenReturn(stats);
        mockMvc.perform(get("/api/v1/flags/stats")
                        .param("from", "2024-03-01T00:00:00Z")
                        .param("to", "2024-03-31T23:59:59Z")
                        .param("category", "ELECTRONICS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFlags").value(42))
                .andExpect(jsonPath("$.byRule.HIGH_AMOUNT").value(20))
                .andExpect(jsonPath("$.bySeverity.CRITICAL").value(12));
    }
    @Test
    @DisplayName("GET /api/v1/flags/stats — returns 400 when 'from' is after 'to'")
    void getStats_fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/flags/stats")
                        .param("from", "2024-03-31T00:00:00Z")
                        .param("to",   "2024-03-01T00:00:00Z")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private FlagSummary buildFlagSummary() {
        return FlagSummary.builder()
                .flagId(1L)
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal("15000.00"))
                .merchant("ACME Corp")
                .category("ELECTRONICS")
                .ruleTriggered(FraudRuleType.HIGH_AMOUNT)
                .severity(Severity.HIGH)
                .score(70)
                .detail("Amount exceeded threshold")
                .status(FlagStatus.OPEN)
                .createdAt(Instant.now())
                .build();
    }
    private TransactionFlagDetail buildTransactionFlagDetail() {
        FlagEntry flagEntry = FlagEntry.builder()
                .flagId(1L)
                .ruleTriggered(FraudRuleType.HIGH_AMOUNT)
                .severity(Severity.HIGH)
                .score(70)
                .detail("Amount exceeded threshold")
                .status(FlagStatus.OPEN)
                .createdAt(Instant.now())
                .build();
        return TransactionFlagDetail.builder()
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal("15000.00"))
                .merchant("ACME Corp")
                .category("ELECTRONICS")
                .latitude(51.5074)
                .longitude(-0.1278)
                .timestamp(Instant.now())
                .processedAt(Instant.now())
                .overallSeverity(Severity.HIGH)
                .totalScore(70)
                .flags(List.of(flagEntry))
                .build();
    }
}
