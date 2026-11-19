package io.joblab.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGeneratorTest {

    @Test
    void escapesQuotesAndNeutralizesFormulaCells() {
        assertThat(ReportGenerator.csvText("SKU,\"A\"")).isEqualTo("\"SKU,\"\"A\"\"\"");
        assertThat(ReportGenerator.csvText("=1+1")).isEqualTo("\"'=1+1\"");
    }
}
