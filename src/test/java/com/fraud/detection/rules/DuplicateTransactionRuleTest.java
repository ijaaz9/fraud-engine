package com.fraud.detection.rules;

@DisplayName("DuplicateTransactionRule")
@ExtendWith(MockitoExtension.class)
class DuplicateTransactionRuleTest {

    @Mock
    private DeduplicationStore deduplicationStore;

    @InjectMocks
    private DuplicateTransactionRule rule;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rule, "windowSeconds", 120L);
        ReflectionTestUtils.setField(rule, "ruleScore", 25);
    }

    @Test
    @DisplayName("should NOT trigger when no duplicate exists in the window")
    void shouldNotTriggerForUniqueTransaction() {
        when(deduplicationStore.isDuplicateAndRecord(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(false);

        RuleEvaluationResult result = rule.evaluate(buildEvent("100.00"));

        assertThat(result.isTriggered()).isFalse();
        assertThat(result.getScore()).isZero();
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.DUPLICATE_TRANSACTION);
    }

    @Test
    @DisplayName("should trigger when a duplicate is detected")
    void shouldTriggerForDuplicateTransaction() {
        when(deduplicationStore.isDuplicateAndRecord(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(true);

        RuleEvaluationResult result = rule.evaluate(buildEvent("100.00"));

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getScore()).isEqualTo(25);
        assertThat(result.getDetail()).contains("user-001").contains("ACME Corp");
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.DUPLICATE_TRANSACTION);
    }

    @Test
    @DisplayName("should pass normalised amount string to the deduplication store")
    void shouldPassNormalisedAmountToStore() {
        when(deduplicationStore.isDuplicateAndRecord(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(false);

        // BigDecimal.toPlainString() on "100.00" should produce "100.00"
        rule.evaluate(buildEvent("100.00"));

        verify(deduplicationStore).isDuplicateAndRecord(
                eq("user-001"),
                eq("ACME Corp"),
                eq("100.00"),
                eq(120L)
        );
    }

    @Test
    @DisplayName("should pass correct window seconds to the deduplication store")
    void shouldPassWindowSecondsToStore() {
        when(deduplicationStore.isDuplicateAndRecord(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(false);

        rule.evaluate(buildEvent("50.00"));

        verify(deduplicationStore).isDuplicateAndRecord(anyString(), anyString(), anyString(), eq(120L));
    }

    private TransactionEvent buildEvent(String amount) {
        return TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal(amount))
                .merchant("ACME Corp")
                .category("RETAIL")
                .latitude(51.5074)
                .longitude(-0.1278)
                .timestamp(Instant.now())
                .build();
    }
}