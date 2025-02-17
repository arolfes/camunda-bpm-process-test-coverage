package org.camunda.bpm.extension.process_test_coverage.junit5

import mu.KLogging
import org.assertj.core.api.Assertions
import org.assertj.core.api.Condition
import org.camunda.bpm.engine.ProcessEngineConfiguration
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.camunda.bpm.extension.junit5.test.ProcessEngineExtension
import org.camunda.bpm.extension.process_test_coverage.engine.CompensationEventCoverageHandler
import org.camunda.bpm.extension.process_test_coverage.engine.ElementCoverageParseListener
import org.camunda.bpm.extension.process_test_coverage.engine.ExecutionContextModelProvider
import org.camunda.bpm.extension.process_test_coverage.model.DefaultCollector
import org.camunda.bpm.extension.process_test_coverage.model.Run
import org.camunda.bpm.extension.process_test_coverage.model.Suite
import org.camunda.bpm.extension.process_test_coverage.util.CoverageReportUtil
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Extension for JUnit 5 which allows the tracking of coverage information for Camunda BPM process tests.
 * Based on the ProcessEngineExtension from the camunda-bpm-junit5 from the camunda community.
 * https://github.com/camunda-community-hub/camunda-bpm-junit5
 *
 * @author Jan Rohwer
 */
class ProcessEngineCoverageExtension(
        /**
         * Log class and test method coverages?
         */
        private val detailedCoverageLogging: Boolean = false,
        /**
         * Is method coverage handling needed?
         */
        private val handleTestMethodCoverage: Boolean = true,
        /**
         * A list of process definition keys excluded from the test run.
         */
        private val excludedProcessDefinitionKeys: List<String> = listOf(),

        ) : ProcessEngineExtension(), BeforeAllCallback, AfterAllCallback {

    companion object : KLogging() {
        @JvmStatic
        fun builder() = Builder()
        @JvmStatic
        fun builder(configurationResource: String) = Builder(configurationResource = configurationResource)
        @JvmStatic
        fun builder(processEngineConfiguration: ProcessEngineConfiguration) = Builder(processEngineConfiguration = processEngineConfiguration)
    }

    /**
     * The state of the current run (class and current method).
     */
    private val coverageCollector = DefaultCollector(ExecutionContextModelProvider())

    /**
     * Conditions to be asserted on the class coverage percentage.
     */
    private val classCoverageAssertionConditions: MutableList<Condition<Double>> = mutableListOf()

    /**
     * Conditions to be asserted on the individual test method coverages.
     */
    private val testMethodNameToCoverageConditions: MutableMap<String, MutableList<Condition<Double>>> = mutableMapOf()

    private var suiteInitialized = false

    override fun postProcessTestInstance(testInstance: Any?, context: ExtensionContext) {
        super.postProcessTestInstance(testInstance, context)
        initializeListeners()
    }

    /**
     * Handles creating the run if a relevant test method is called.
     */
    override fun beforeTestExecution(context: ExtensionContext) {
        super.beforeTestExecution(context)
        if (isRelevantTestMethod()) {
            if (!suiteInitialized) {
                initializeSuite(context)
            }
            // method name is set only on test methods (not on classes or suites)
            val runId: String = context.uniqueId
            coverageCollector.createRun(Run(runId, context.requiredTestMethod.name), coverageCollector.activeSuite.id)
            coverageCollector.activateRun(runId)
        }
    }

    /**
     * Handles evaluating the test method coverage after a relevant test method is finished.
     */
    override fun afterTestExecution(context: ExtensionContext) {
        if (handleTestMethodCoverage && isRelevantTestMethod()) {
            handleTestMethodCoverage(context)
        }
        super.afterTestExecution(context)
    }

    /**
     * Initializes the suite for all upcoming tests.
     */
    override fun beforeAll(context: ExtensionContext) {
        initializeSuite(context)
    }

    private fun initializeSuite(context: ExtensionContext) {
        val suiteId: String = context.uniqueId
        coverageCollector.createSuite(Suite(suiteId, context.requiredTestClass.name))
        coverageCollector.setExcludedProcessDefinitionKeys(excludedProcessDefinitionKeys)
        coverageCollector.activateSuite(suiteId)
        suiteInitialized = true
    }

    /**
     * If the extension is registered on the class level, log and assert the coverage and create a
     * graphical report. For the class coverage to work all the test method
     * deployments have to be equal.
     */
    override fun afterAll(context: ExtensionContext) {
        val suite = coverageCollector.activeSuite

        // Make sure the class coverage deals with the same deployments for
        // every test method
        // classCoverage.assertAllDeploymentsEqual();
        val suiteCoveragePercentage = suite.calculateCoverage(coverageCollector.getModels())

        // Log coverage percentage
        logger.info("${suite.name} test class coverage is: $suiteCoveragePercentage")
        logCoverageDetail(suite)

        assertCoverage(suiteCoveragePercentage, classCoverageAssertionConditions)

        // Create graphical report
        CoverageReportUtil.createReport(coverageCollector)
        CoverageReportUtil.createJsonReport(coverageCollector)
    }

    /**
     * Sets the test run state for the coverage listeners. logging.
     * {@see ProcessCoverageInMemProcessEngineConfiguration}
     */
    private fun initializeListeners() {
        val processEngineConfiguration = this.processEngineConfiguration
        val bpmnParseListeners = processEngineConfiguration.customPostBPMNParseListeners
        for (parseListener in bpmnParseListeners) {
            if (parseListener is ElementCoverageParseListener) {
                parseListener.setCoverageState(coverageCollector)
            }
        }

        // Compensation event handler
        val compensationEventHandler = processEngineConfiguration.getEventHandler("compensate")
        if (compensationEventHandler is CompensationEventCoverageHandler) {
            compensationEventHandler.setCoverageState(coverageCollector)
        } else {
            logger.warn("CompensationEventCoverageHandler not registered with process engine configuration!"
                    + " Compensation boundary events coverage will not be registered.")
        }
    }

    /**
     * Logs and asserts the test method coverage and creates a graphical report.
     *
     * @param context extension context
     */
    private fun handleTestMethodCoverage(context: ExtensionContext) {
        val suite = coverageCollector.activeSuite
        val run = suite.getRun(context.uniqueId) ?: return
        val coveragePercentage = run.calculateCoverage(coverageCollector.getModels())

        // Log coverage percentage
        logger.info("${run.name} test method coverage is $coveragePercentage")
        logCoverageDetail(run)

        testMethodNameToCoverageConditions[run.name]?.let {
            assertCoverage(coveragePercentage, it)
        }
    }

    /**
     * Determines if the provided Description describes a method relevant for coverage metering. This is the case,
     * if the Description is provided for an atomic test and the test has access to deployed process.
     *
     * @return `true` if the description is provided for the relevant method.
     */
    private fun isRelevantTestMethod(): Boolean {
        return super.deploymentId != null // deployment is set
                // the deployed process is not excluded
                && processEngine.repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .list().any {
                    !excludedProcessDefinitionKeys.contains(it.key)
                }
    }

    /**
     * Logs the string representation of the passed suite object.
     */
    private fun logCoverageDetail(suite: Suite) {
        if (logger.isDebugEnabled && detailedCoverageLogging) {
            logger.debug(suite.toString())
        }
    }

    /**
     * Logs the string representation of the passed run object.
     */
    private fun logCoverageDetail(run: Run) {
        if (logger.isDebugEnabled && detailedCoverageLogging) {
            logger.debug(run.toString())
        }
    }

    private fun assertCoverage(coverage: Double, conditions: List<Condition<Double>>) {
        conditions.forEach { Assertions.assertThat(coverage).satisfies(it) }
    }

    fun addTestMethodCoverageCondition(methodName: String, condition: Condition<Double>) =
            testMethodNameToCoverageConditions.getOrPut(methodName) { mutableListOf() }.add(condition)

    private fun addClassCoverageAtLeast(percentage: Double) =
            classCoverageAssertionConditions.add(
                    Condition<Double>({ p -> p >= percentage }, "matches if the coverage ratio is at least $percentage")
            )

    data class Builder(
            var configurationResource: String? = null,
            val processEngineConfiguration: ProcessEngineConfiguration? = null,
            var detailedCoverageLogging: Boolean = false,
            var handleTestMethodCoverage: Boolean = true,
            var coverageAtLeast: Double? = null,
            var excludedProcessDefinitionKeys: List<String> = listOf(),
            var optionalAssertCoverageAtLeastProperty: String = DEFAULT_ASSERT_AT_LEAST_PROPERTY
    ) {

        companion object {

            /**
             * If you set this property to a ratio (e.g. "1.0" for full coverage),
             * the Extension will fail the test run if the coverage is less.<br></br>
             * Example parameter for running java:<br></br>
             * `-Dorg.camunda.bpm.extension.process_test_coverage.ASSERT_AT_LEAST=1.0`
             */
            const val DEFAULT_ASSERT_AT_LEAST_PROPERTY = "org.camunda.bpm.extension.process_test_coverage.ASSERT_AT_LEAST"

        }

        /**
         * Set the configuration resource for initializing the process engine.
         */
        @Deprecated("Pass the configuration resource directly when creating the builder",
                ReplaceWith("ProcessEngineCoverageExtension.builder(configurationResource)",
                        "org.camunda.bpm.extension.process_test_coverage.junit5.ProcessEngineCoverageExtension"))
        fun configurationResource(configurationResource: String): Builder {
            return if (this.configurationResource != null) {
                logger.warn { "configuration resource ${this.configurationResource} already configured, ignoring $configurationResource" }
                this
            } else if (this.processEngineConfiguration != null) {
                logger.warn { "process engine configuration ${this.processEngineConfiguration} already configured, ignoring $configurationResource" }
                this
            } else {
                this.apply { this.configurationResource = configurationResource }
            }
        }

        /**
         * Turns on detailed coverage logging in debug scope.
         */
        fun withDetailedCoverageLogging() = this.apply { detailedCoverageLogging = true }

        /**
         * Controls whether method coverage should be evaluated.
         */
        fun handleTestMethodCoverage(handleTestMethodCoverage: Boolean) = this.apply { this.handleTestMethodCoverage = handleTestMethodCoverage }

        /**
         * Asserts if the class coverage is greater than the passed percentage.
         * @param percentage minimal percentage for class coverage
         */
        fun assertClassCoverageAtLeast(percentage: Double) = this.apply {
            coverageAtLeast = percentage.checkPercentage()
        }

        /**
         * Specifies keys of process definitions, that should be excluded from the coverage analysis.
         */
        fun excludeProcessDefinitionKeys(vararg processDefinitionKeys: String) = this.apply { excludedProcessDefinitionKeys = processDefinitionKeys.toList() }

        /**
         * Specifies the key of the system property for optionally reading a minimal assertion coverage.
         */
        fun optionalAssertCoverageAtLeastProperty(property: String) = this.apply { optionalAssertCoverageAtLeastProperty = property }

        private fun coverageFromSystemProperty(key: String): Double? {
            return System.getProperty(key)?.let {
                try {
                    it.toDouble().checkPercentage()
                } catch (e: NumberFormatException) {
                    throw RuntimeException("BAD TEST CONFIGURATION: system property \"$key\" must be double")
                }
            }
        }

        fun build(): ProcessEngineCoverageExtension {
            return ProcessEngineCoverageExtension(
                    detailedCoverageLogging = detailedCoverageLogging,
                    handleTestMethodCoverage = handleTestMethodCoverage,
                    excludedProcessDefinitionKeys = excludedProcessDefinitionKeys
            ).apply {
                coverageFromSystemProperty(this@Builder.optionalAssertCoverageAtLeastProperty)?.let {
                    addClassCoverageAtLeast(it)
                }
                this@Builder.coverageAtLeast?.let { addClassCoverageAtLeast(it) }
                this@Builder.configurationResource?.let { this.configurationResource(it) }
                if (this@Builder.processEngineConfiguration != null) {
                    processEngine = this@Builder.processEngineConfiguration.buildProcessEngine()
                    processEngineConfiguration = processEngine.processEngineConfiguration as ProcessEngineConfigurationImpl
                } else if (processEngine == null) {
                    initializeProcessEngine()
                }
                initializeListeners()
            }
        }
    }

}

fun Double.checkPercentage() =
    if (0 > this || this > 1) {
        throw RuntimeException(
                "BAD TEST CONFIGURATION: coverageAtLeast " + this + " (" + 100 * this + "%) ")
    } else this