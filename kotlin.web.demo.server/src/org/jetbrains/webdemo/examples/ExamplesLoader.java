/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.webdemo.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.collect.Lists;
import com.intellij.openapi.extensions.Extensions;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.webdemo.ApplicationSettings;
import org.jetbrains.webdemo.JsonUtils;
import org.jetbrains.webdemo.ProjectFile;
import org.pegdown.LinkRenderer;
import org.pegdown.PegDownProcessor;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.ast.CodeNode;
import org.pegdown.ast.VerbatimNode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExamplesLoader {

    public static void loadAllExamples() {
        ExamplesFolder.ROOT_FOLDER = loadFolder(ApplicationSettings.EXAMPLES_DIRECTORY, "/", new ArrayList<ObjectNode>());
    }

    private static ExamplesFolder loadFolder(String path, String url, List<ObjectNode> parentCommonFiles) {
        File manifestFile = new File(path + File.separator + "manifest.json");
        try (BufferedInputStream reader = new BufferedInputStream(new FileInputStream(manifestFile))) {
            ObjectNode manifest = (ObjectNode) JsonUtils.getObjectMapper().readTree(reader);
            String name = new File(path).getName();
            Map<String, Example> examples = new LinkedHashMap<>();
            Map<String, ExamplesFolder> childFolders = new LinkedHashMap<>();
            List<ObjectNode> commonFiles = new ArrayList<>();
            commonFiles.addAll(parentCommonFiles);
            boolean taskFolder = manifest.has("taskFolder") ? manifest.get("taskFolder").asBoolean() : false;

            if (manifest.has("files")) {
                for (JsonNode node : manifest.get("files")) {
                    ObjectNode fileManifest = (ObjectNode) node;
                    fileManifest.put("path", path + File.separator + fileManifest.get("filename").asText());
                    commonFiles.add(fileManifest);
                }
            }

            if (manifest.has("folders")) {
                for (JsonNode node : manifest.get("folders")) {
                    String folderName = node.textValue();
                    childFolders.put(folderName,
                            loadFolder(path + File.separator + folderName, url + folderName + "/", commonFiles));
                }
            }

            if (manifest.has("examples")) {
                Example previousExample = null;
                for (JsonNode node : manifest.get("examples")) {
                    String projectName = node.textValue();
                    String projectPath = path + File.separator + projectName;
                    Example example = loadProject(
                            projectPath,
                            url,
                            ApplicationSettings.LOAD_TEST_VERSION_OF_EXAMPLES,
                            commonFiles,
                            previousExample
                    );
                    previousExample = example;
                    examples.put(projectName, example);
                }
            }

            return new ExamplesFolder(name, url, taskFolder, examples, childFolders);
        } catch (IOException e) {
            System.err.println("Can't load folder: " + e.toString());
            return null;
        }
    }

    private static Example loadProject(
            String path,
            String parentUrl,
            boolean loadTestVersion,
            List<ObjectNode> commonFilesManifests,
            @Nullable Example previousExample
    ) throws IOException {
        File manifestFile = new File(path + File.separator + "manifest.json");
        try (BufferedInputStream reader = new BufferedInputStream(new FileInputStream(manifestFile))) {
            ObjectNode manifest = (ObjectNode) JsonUtils.getObjectMapper().readTree(reader);

            String name = new File(path).getName();
            String id = (parentUrl + name).replaceAll(" ", "%20");
            String args = manifest.has("args") ? manifest.get("args").asText() : "";
            String runConfiguration = manifest.get("confType").asText();
            String expectedOutput;
            List<String> readOnlyFileNames = new ArrayList<>();
            List<ProjectFile> files = new ArrayList<>();
            List<ProjectFile> hiddenFiles = new ArrayList<>();
            if (manifest.has("expectedOutput")) {
                expectedOutput = manifest.get("expectedOutput").asText();
            } else if (manifest.has("expectedOutputFile")) {
                Path expectedOutputFilePath = Paths.get(path + File.separator + manifest.get("expectedOutputFile").asText());
                expectedOutput = new String(Files.readAllBytes(expectedOutputFilePath));
            } else {
                expectedOutput = null;
            }
            String help = null;
            File helpFile = new File(path + File.separator + "task.md");
            if (helpFile.exists()) {
                PegDownProcessor processor = new PegDownProcessor(org.pegdown.Extensions.FENCED_CODE_BLOCKS);
                String helpInMarkdown = new String(Files.readAllBytes(helpFile.toPath()));
                help = new GFMNodeSerializer().toHtml(processor.parseMarkdown(helpInMarkdown.toCharArray()));
            }

            List<TaskWindow> taskWindows = null;
            if (manifest.has("taskWindows")) {
                taskWindows = JsonUtils.getObjectMapper().readValue(manifest.get("taskWindows").toString(), TypeFactory.defaultInstance().constructCollectionType(List.class, TaskWindow.class));
            }

            List<JsonNode> fileManifests = manifest.has("files") ? Lists.newArrayList(manifest.get("files")) : new ArrayList<JsonNode>();
            fileManifests.addAll(commonFilesManifests);
            for (JsonNode fileDescriptor : fileManifests) {

                if (loadTestVersion &&
                        fileDescriptor.has("skipInTestVersion") &&
                        fileDescriptor.get("skipInTestVersion").asBoolean()) {
                    continue;
                }

                String filePath = fileDescriptor.has("path") ?
                        fileDescriptor.get("path").asText() :
                        path + File.separator + fileDescriptor.get("filename").textValue();
                ExampleFile file = loadProjectFile(filePath, id, fileDescriptor);
                if (!loadTestVersion && file.getType().equals(ProjectFile.Type.SOLUTION_FILE)) {
                    continue;
                }
                if (!file.isModifiable()) {
                    readOnlyFileNames.add(file.getName());
                }

                if (file.isHidden()) {
                    hiddenFiles.add(file);
                } else {
                    files.add(file);
                }
            }
            loadDefaultFiles(path, id, files, loadTestVersion);

            return new Example(
                    id,
                    name,
                    args,
                    runConfiguration,
                    id,
                    expectedOutput,
                    files,
                    hiddenFiles,
                    readOnlyFileNames,
                    taskWindows,
                    previousExample,
                    help);
        } catch (IOException e) {
            System.err.println("Can't load project: " + e.toString());
            return null;
        }
    }

    private static void loadDefaultFiles(
            String folderPath, String projectId, List<ProjectFile> files, boolean loadTestVersion
    ) throws IOException {
        File solutionFile = new File(folderPath + File.separator + "Solution.kt");
        File testFile = new File(folderPath + File.separator + "Test.kt");
        File taskFile = new File(folderPath + File.separator + "Task.kt");

        if (testFile.exists() && !isAlreadyLoaded(files, testFile.getName())) {
            String testText = new String(Files.readAllBytes(testFile.toPath())).replaceAll("\r\n", "\n");
            files.add(0, new TestFile(testText, projectId + "/" + testFile.getName()));
        }

        if (loadTestVersion) {
            if (solutionFile.exists() && !isAlreadyLoaded(files, solutionFile.getName())) {
                String solutionText = new String(Files.readAllBytes(solutionFile.toPath())).replaceAll("\r\n", "\n");
                files.add(0, new SolutionFile(solutionText, projectId + "/" + solutionFile.getName()));
            }
        } else {
            if (taskFile.exists() && !isAlreadyLoaded(files, taskFile.getName())) {
                String taskText = new String(Files.readAllBytes(taskFile.toPath())).replaceAll("\r\n", "\n");
                files.add(0, new TaskFile(taskText, projectId + "/" + taskFile.getName()));
            }
        }

    }

    private static boolean isAlreadyLoaded(List<ProjectFile> files, String name) {
        for (ProjectFile file : files) {
            if (file.getName().equals(name)) return true;
        }
        return false;
    }

    private static ExampleFile loadProjectFile(String path, String projectUrl, JsonNode fileManifest) throws IOException {
        try {
            String fileName = fileManifest.get("filename").textValue();
            boolean modifiable = fileManifest.get("modifiable").asBoolean();
            boolean hidden = fileManifest.has("hidden") ? fileManifest.get("hidden").asBoolean() : false;
            String confType = fileManifest.has("confType") ? fileManifest.get("confType").asText() : null;
            File file = new File(path);
            String fileContent = new String(Files.readAllBytes(file.toPath())).replaceAll("\r\n", "\n");
            String filePublicId = (projectUrl + "/" + fileName).replaceAll(" ", "%20");
            ProjectFile.Type fileType = null;
            if (!fileManifest.has("type")) {
                fileType = ProjectFile.Type.KOTLIN_FILE;
            } else if (fileManifest.get("type").asText().equals("kotlin-test")) {
                fileType = ProjectFile.Type.KOTLIN_TEST_FILE;
            } else if (fileManifest.get("type").asText().equals("solution")) {
                fileType = ProjectFile.Type.SOLUTION_FILE;
            } else if (fileManifest.get("type").asText().equals("java")) {
                fileType = ProjectFile.Type.JAVA_FILE;
            }
            return new ExampleFile(fileName, fileContent, filePublicId, fileType, confType, modifiable, hidden);
        } catch (IOException e) {
            System.err.println("Can't load file: " + e.toString());
            return null;
        }
    }
}

class GFMNodeSerializer extends ToHtmlSerializer {
    public GFMNodeSerializer() {
        super(new LinkRenderer());
    }

    public void visit(VerbatimNode node) {
        String codeMirrorType = "";
        switch (node.getType()) {
            case "kotlin":
                codeMirrorType = "kotlin";
                break;
            case "java":
                codeMirrorType = "text/x-java";
                break;
        }
        if (!codeMirrorType.equals("")) {
            printer.print("<pre><code data-lang=\"" + codeMirrorType + "\">");
        } else {
            printer.print("<pre><code>");
        }
        printer.printEncoded(node.getText());
        printer.print("</code></pre>");
    }
}
