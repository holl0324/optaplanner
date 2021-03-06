/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.optaplanner.core.impl.score.director.drools.testgen;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.optaplanner.core.api.score.holder.ScoreHolder;
import org.optaplanner.core.impl.score.definition.ScoreDefinition;
import org.optaplanner.core.impl.score.director.drools.DroolsScoreDirector;
import org.optaplanner.core.impl.score.director.drools.testgen.fact.TestGenFact;
import org.optaplanner.core.impl.score.director.drools.testgen.operation.TestGenKieSessionOperation;
import org.optaplanner.core.impl.score.director.drools.testgen.operation.TestGenKieSessionUpdate;
import org.optaplanner.core.impl.score.director.drools.testgen.reproducer.TestGenCorruptedScoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestGenTestWriter {

    private static final Logger logger = LoggerFactory.getLogger(TestGenTestWriter.class);
    private StringBuilder sb;
    private TestGenKieSessionJournal journal;
    private String className;
    private List<String> scoreDrlList = Collections.emptyList();
    private List<File> scoreDrlFileList = Collections.emptyList();
    private ScoreDefinition<?> scoreDefinition;
    private boolean constraintMatchEnabled;
    private TestGenCorruptedScoreException scoreEx;

    public void print(TestGenKieSessionJournal journal, Writer w) {
        print(journal);
        writeTest(w);
    }

    public void print(TestGenKieSessionJournal journal, File testFile) {
        print(journal);
        writeTestFile(testFile);
    }

    private void print(TestGenKieSessionJournal journal) {
        this.journal = journal;
        this.sb = new StringBuilder(1 << 15); // 2^15 initial capacity
        printInit();
        printSetup();
        printTest();
    }

    private void printInit() {
        sb.append("package org.optaplanner.testgen;\n\n");
        List<String> imports = new ArrayList<>();
        imports.add("org.junit.Before");
        imports.add("org.junit.Test");
        imports.add("org.kie.api.KieServices");
        imports.add("org.kie.api.builder.KieFileSystem");
        imports.add("org.kie.api.runtime.KieContainer");
        imports.add("org.kie.api.runtime.KieSession");
        if (!scoreDrlFileList.isEmpty()) {
            imports.add("java.io.File");
        }
        if (scoreDefinition != null) {
            imports.add("org.junit.Assert");
            imports.add(ScoreHolder.class.getCanonicalName());
            imports.add(scoreDefinition.getClass().getCanonicalName());
        }

        Stream<String> classes = Stream.concat(
                // imports from facts
                journal.getFacts().stream()
                .flatMap(fact -> fact.getImports().stream()),
                // imports from update operations (including shadow variable updates with inline values)
                journal.getMoveOperations().stream()
                .filter(op -> op instanceof TestGenKieSessionUpdate)
                .flatMap(up -> {
                    return ((TestGenKieSessionUpdate) up).getValue().getImports().stream();
                })
        )
                .filter(cls -> !cls.getPackage().getName().equals("java.lang"))
                .map(cls -> cls.getCanonicalName());

        Stream.concat(imports.stream(), classes)
                .distinct()
                .sorted()
                .forEach(cls -> sb.append(String.format("import %s;\n", cls)));

        sb.append("\n")
                .append("public class ").append(className).append(" {\n\n")
                .append("    KieContainer kieContainer;\n")
                .append("    KieSession kieSession;\n");
        if (scoreDefinition != null) {
            sb.append(String.format("    ScoreHolder scoreHolder = new %s().buildScoreHolder(%s);\n",
                    scoreDefinition.getClass().getSimpleName(), constraintMatchEnabled));
        }

        for (TestGenFact fact : journal.getFacts()) {
            fact.printInitialization(sb);
        }
        sb.append("\n");
    }

    private void printSetup() {
        sb
                .append("    @Before\n")
                .append("    public void setUp() {\n")
                .append("        KieServices kieServices = KieServices.Factory.get();\n")
                .append("        KieFileSystem kfs = kieServices.newKieFileSystem();\n");
        scoreDrlFileList.forEach(file -> {
            sb
                    .append("        kfs.write(kieServices.getResources()\n")
                    .append("                .newFileSystemResource(new File(\"").append(file.getAbsoluteFile())
                    .append("\"), \"UTF-8\"));\n");
        });
        scoreDrlList.forEach(drl -> {
            sb
                    .append("        kfs.write(kieServices.getResources()\n")
                    .append("                .newClassPathResource(\"").append(drl).append("\"));\n");
        });
        sb
                .append("        kieServices.newKieBuilder(kfs).buildAll();\n")
                .append("        kieContainer = kieServices.newKieContainer(kieServices.getRepository().getDefaultReleaseId());\n")
                .append("        kieSession = kieContainer.newKieSession();\n\n");
        if (scoreDefinition != null) {
            sb.append("        kieSession.setGlobal(\"").append(DroolsScoreDirector.GLOBAL_SCORE_HOLDER_KEY)
                    .append("\", scoreHolder);\n\n");
        }
        for (TestGenFact fact : journal.getFacts()) {
            fact.printSetup(sb);
        }
        sb.append("\n");
        for (TestGenKieSessionOperation insert : journal.getInitialInserts()) {
            insert.print(sb);
        }
        sb.append("    }\n\n");
    }

    private void printTest() {
        sb
                .append("    @Test\n")
                .append("    public void test() {\n");
        for (TestGenKieSessionOperation op : journal.getMoveOperations()) {
            op.print(sb);
        }
        if (scoreEx != null) {
            sb
                    .append("        // This is the corrupted score, just to make sure the bug is reproducible\n")
                    .append("        Assert.assertEquals(\"").append(scoreEx.getWorkingScore())
                    .append("\", scoreHolder.extractScore(0).toString());\n");
            // demonstrate the uncorrupted score
            sb
                    .append("        kieSession = kieContainer.newKieSession();\n")
                    .append("        scoreHolder = new ").append(scoreDefinition.getClass().getSimpleName())
                    .append("().buildScoreHolder(").append(constraintMatchEnabled).append(");\n")
                    .append("        kieSession.setGlobal(\"").append(DroolsScoreDirector.GLOBAL_SCORE_HOLDER_KEY)
                    .append("\", scoreHolder);\n");

            sb
                    .append("\n\n        // Insert everything into a fresh session to see the uncorrupted score\n");
            for (TestGenKieSessionOperation insert : journal.getInitialInserts()) {
                insert.print(sb);
            }
            sb
                    .append("        kieSession.fireAllRules();\n")
                    .append("        Assert.assertEquals(\"").append(scoreEx.getUncorruptedScore())
                    .append("\", scoreHolder.extractScore(0).toString());\n");
        }
        sb
                .append("    }\n")
                .append("}\n");
    }

    private void writeTestFile(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.exists()) {
            if (!parent.mkdirs()) {
                logger.warn("Couldn't create directory: {}", parent);
            }
        }
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(file);
        } catch (FileNotFoundException ex) {
            logger.error("Cannot open test file: " + file.toString(), ex);
            return;
        }
        OutputStreamWriter osw;
        try {
            osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            logger.error("Can't open", ex);
            return;
        }
        writeTest(osw);
    }

    private void writeTest(Writer w) {
        try {
            w.append(sb);
        } catch (IOException ex) {
            logger.error("Can't write", ex);
        } finally {
            try {
                w.close();
            } catch (IOException ex) {
                logger.error("Can't close", ex);
            }
        }
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setScoreDrlList(List<String> scoreDrlList) {
        this.scoreDrlList = scoreDrlList == null ? Collections.emptyList() : scoreDrlList;
    }

    public void setScoreDrlFileList(List<File> scoreDrlFileList) {
        this.scoreDrlFileList = scoreDrlFileList == null ? Collections.emptyList() : scoreDrlFileList;
    }

    public void setScoreDefinition(ScoreDefinition<?> scoreDefinition) {
        this.scoreDefinition = scoreDefinition;
    }

    public void setConstraintMatchEnabled(boolean constraintMatchEnabled) {
        this.constraintMatchEnabled = constraintMatchEnabled;
    }

    public void setCorruptedScoreException(TestGenCorruptedScoreException ex) {
        this.scoreEx = ex;
    }

}
