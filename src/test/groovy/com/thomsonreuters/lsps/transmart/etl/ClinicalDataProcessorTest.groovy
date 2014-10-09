package com.thomsonreuters.lsps.transmart.etl

import com.thomsonreuters.lsps.transmart.Fixtures
import groovy.sql.Sql
import spock.lang.Specification

import static com.thomsonreuters.lsps.transmart.Fixtures.getAdditionalStudiesDir
import static com.thomsonreuters.lsps.transmart.Fixtures.studyDir
import static com.thomsonreuters.lsps.transmart.etl.matchers.SqlMatchers.*
import static org.junit.Assert.assertThat

/**
 * Created by bondarev on 2/24/14.
 */
class ClinicalDataProcessorTest extends Specification implements ConfigAwareTestCase {
    private ClinicalDataProcessor _processor

    String studyName = 'Test Study'
    String studyId = 'GSE0'

    void setup() {
        ConfigAwareTestCase.super.setUp()
        runScript('I2B2_LOAD_CLINICAL_DATA.sql')
    }

    ClinicalDataProcessor getProcessor() {
        _processor ?: (_processor = new ClinicalDataProcessor(config))
    }

    void testItLoadsAge() {
        setup:
        processor.process(Fixtures.getClinicalData(studyName, studyId),
                [name: studyName, node: "Test Studies\\${studyName}".toString()])
        expect:
        assertThat(db, hasRecord('i2b2demodata.patient_dimension',
                ['sourcesystem_cd': "${studyId}:HCC827"], [age_in_years_num: 20]))
    }

    def 'it should produce SummaryStatistic.txt'() {
        when:
        def clinicalDataDir = Fixtures.getClinicalData(studyName, studyId)
        def expectedFile = new File(clinicalDataDir, 'ExpectedSummaryStatistic.txt')
        def actualFile = new File(clinicalDataDir, 'SummaryStatistic.txt')
        actualFile.delete()
        processor.process(clinicalDataDir,
                [name: studyName, node: "Test Studies\\${studyName}".toString()])
        then:
        actualFile.exists()
        actualFile.text == expectedFile.text
    }

    def "it should collect statistic"() {
        setup:
        database.withSql { sql ->
            processor.processFiles(Fixtures.getClinicalData(studyName, studyId), sql as Sql,
                    [name: studyName, node: "Test Studies\\${studyName}".toString()])
        }
        def statistic = processor.statistic
        expect:
        statistic != null
        statistic.tables.keySet() as List == ['TST001.txt', 'TST_DEMO.txt']
        def demo = statistic.tables.'TST_DEMO.txt'
        demo != null
        demo.variables.keySet() as List == ['SUBJ_ID', 'Age In Years', 'Sex', 'Assessment Date', 'Language']

        def subjId = demo.variables.SUBJ_ID
        subjId.notEmptyValuesCount == 9
        subjId.emptyValuesCount == 0

        def age = demo.variables.'Age In Years'
        age.notEmptyValuesCount == 9
        age.emptyValuesCount == 0
        age.mean.round(6) == 30.555556
        age.median == 20.9
        age.min == 11.5
        age.max == 90.0
        age.standardDerivation.round(6) == 23.734843

        def sex = demo.variables.'Sex'
        sex.notEmptyValuesCount == 8
        sex.emptyValuesCount == 1
        sex.factor.counts.Female == 5
        sex.factor.counts.Male == 3

        def assessmentDate = demo.variables.'Assessment Date'
        assessmentDate.notEmptyValuesCount == 9
        assessmentDate.emptyValuesCount == 0

        def language = demo.variables.'Language'
        language.notEmptyValuesCount == 3
        language.emptyValuesCount == 6
    }

    void testItLoadsData() {
        expect:
        String conceptPath = "\\Test Studies\\${studyName}\\"
        String conceptPathForPatient = conceptPath + "Biomarker Data\\Mutations\\TST001 (Entrez ID: 1956)\\AA mutation\\"

        processor.process(
                new File(studyDir(studyName, studyId), "ClinicalDataToUpload"),
                [name: studyName, node: "Test Studies\\${studyName}".toString()])

        assertThat(sql, hasPatient('HCC2935').inTrial(studyId))
        assertThat(sql, hasNode(conceptPathForPatient).withPatientCount(9))
        assertThat(sql, hasNode(conceptPathForPatient + 'T790M\\'))
    }

    void testItMergesData() {
        expect:
        String conceptPath = "\\Test Studies\\${studyName}\\"
        String conceptPathForPatient = conceptPath + "Biomarker Data\\Mutations\\TST001 (Entrez ID: 1956)\\AA mutation\\"
        String subjId = 'HCC2935'

        processor.process(
                new File(studyDir(studyName, studyId), "ClinicalDataToUpload"),
                [name: studyName, node: "Test Studies\\${studyName}".toString()])

        assertThat(sql, hasPatient(subjId).inTrial(studyId))
        assertThat(sql, hasNode(conceptPathForPatient).withPatientCount(9))
        assertThat(sql, hasNode(conceptPathForPatient + 'T790M\\'))

        processor.process(
                new File(studyDir(studyName, studyId, additionalStudiesDir), "ClinicalDataToUpload"),
                [name: studyName, node: "Test Studies\\${studyName}".toString()])

        assertThat(sql, hasPatient(subjId).inTrial(studyId))
        assertThat(sql, hasNode(conceptPathForPatient).withPatientCount(9))
        assertThat(sql, hasNode(conceptPathForPatient + 'T790M TEST\\'))

        assertThat(sql, hasNode($/${conceptPath}Biomarker Data\Mutations\TST002 (Entrez ID: 324)\/$))
    }
}
