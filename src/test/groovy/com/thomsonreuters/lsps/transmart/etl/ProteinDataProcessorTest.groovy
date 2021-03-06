package com.thomsonreuters.lsps.transmart.etl

import com.thomsonreuters.lsps.transmart.sql.DatabaseType

import static com.thomsonreuters.lsps.transmart.etl.matchers.SqlMatchers.hasNode
import static com.thomsonreuters.lsps.transmart.etl.matchers.SqlMatchers.hasPatient
import static com.thomsonreuters.lsps.transmart.etl.matchers.SqlMatchers.hasRecord
import static com.thomsonreuters.lsps.transmart.etl.matchers.SqlMatchers.hasSample
import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.CoreMatchers.notNullValue
import static org.junit.Assert.assertThat

class ProteinDataProcessorTest extends GroovyTestCase implements ConfigAwareTestCase {
    private ProteinDataProcessor _processor
    String studyName = 'Test Protein Study'
    String studyId = 'GSE37425'
    String platformId = 'RBM999'

    String platformIdWithoutPeptide = 'RBM888'
    String studyIdWithoutPeptide = 'GSE374251'
    String studyNameWithoutPeptide = 'Test Protein Study 2'

    ProteinDataProcessor getProcessor() {
        _processor ?: (_processor = new ProteinDataProcessor(config))
    }

    @Override
    void setUp() {
        ConfigAwareTestCase.super.setUp()
        sql.execute('delete from i2b2demodata.observation_fact where modifier_cd = ? or sourcesystem_cd = ?', studyId, studyId)
        sql.execute('delete from deapp.de_subject_sample_mapping where trial_name = ?', studyId)
        runScript('I2B2_LOAD_PROTEOMICS_ANNOT.sql')
        if (database?.databaseType == DatabaseType.Postgres) {
            runScript('I2B2_PROCESS_PROTEOMICS_DATA.sql')
        }
    }

    void assertThatSampleIsPresent(String sampleId, sampleData, currentStudyId, currentPlatformId) {
        def sample = sql.firstRow('select * from deapp.de_subject_sample_mapping where trial_name = ? and sample_cd = ?',
                currentStudyId, sampleId)
        assertThat(sample, notNullValue())

        sampleData.each { gene_symbol, value ->
            def rows = sql.rows("select d.zscore from deapp.de_subject_protein_data d " +
                    "inner join deapp.de_protein_annotation a on d.protein_annotation_id = a.id " +
                    "where a.gpl_id = ? and d.assay_id = ? and d.gene_symbol = ?",
                    currentPlatformId, sample.assay_id, gene_symbol)
            assertThat(rows?.size(), equalTo(1))
            assertEquals(rows[0].zscore as double, value as double, 0.001)
        }
    }


    void testItLoadsData() {
        processor.process(
                new File("fixtures/Test Studies/${studyName}/ProteinDataToUpload"),
                [name: studyName, node: "Test Studies\\${studyName}".toString()])
        assertThat(db, hasSample(studyId, 'P50440'))
        assertThat(db, hasPatient('GSM918945').inTrial(studyId))
        assertThat(db, hasNode("\\Test Studies\\${studyName}\\Biomarker Data\\Test Protein Platform\\").
                withPatientCount(5))

        assertThat(db, hasRecord('deapp.de_subject_sample_mapping',
                [trial_name: studyId, gpl_id: platformId, subject_id: 'GSM918944', sample_cd: 'P50440'],
                [platform: 'PROTEIN']))

        assertThat(db, hasRecord('deapp.de_subject_protein_data',
                [trial_name: studyId, subject_id: 'GSM918946', gene_symbol: 'P50440'],
                [component: 'RPPGFSPFR(QTF-2)']))

        assertThatSampleIsPresent('P50440', ['O00231': 0.02146], studyId, platformId)
    }

    void testItLoadsDataWithoutPeptide() {
        processor.process(
                new File("fixtures/Test Studies/${studyNameWithoutPeptide}/ProteinDataToUpload"),
                [name: studyNameWithoutPeptide, node: "Test Studies\\${studyNameWithoutPeptide}".toString()])
        assertThat(db, hasSample(studyIdWithoutPeptide, 'P504401'))
        assertThat(db, hasPatient('GSM9189451').inTrial(studyIdWithoutPeptide))
        assertThat(db, hasNode("\\Test Studies\\${studyNameWithoutPeptide}\\Biomarker Data\\Test Protein Platform 2\\").
                withPatientCount(5))

        assertThat(db, hasRecord('deapp.de_subject_sample_mapping',
                [trial_name: studyIdWithoutPeptide, gpl_id: platformIdWithoutPeptide, subject_id: 'GSM9189441', sample_cd: 'P504401'],
                [platform: 'PROTEIN']))

        assertThat(db, hasRecord('deapp.de_subject_sample_mapping',
                [trial_name: studyIdWithoutPeptide, gpl_id: platformIdWithoutPeptide, subject_id: 'GSM9189441', sample_cd: 'P504401'],
                [platform: 'PROTEIN']))

        assertThat(db, hasRecord('deapp.de_subject_protein_data',
                [trial_name: studyIdWithoutPeptide, subject_id: 'GSM9189461', gene_symbol: 'P026471'],
                [component: 'P026471']))

        assertThatSampleIsPresent('P504401', ['O002311': 0.02146], studyIdWithoutPeptide, platformIdWithoutPeptide)
    }
}
