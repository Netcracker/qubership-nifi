/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.cloud.nifi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;

@Component
public class XmlConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(XmlConfigValidator.class);

    @Value("${config.file.path}")
    private String path;
    @Value("${config.main.path}")
    private String mainConfigDirectoryPath;
    @Value("${config.restore.path}")
    private String restoreDirectoryPath;

    private String mainAuthorizationsFilePath;

    private String mainUsersFilePath;


    public void validate() throws IOException, ParserConfigurationException {
        if (shouldSkipValidation()) return;

        log.info("Restore directory path: {}",restoreDirectoryPath);

        mainAuthorizationsFilePath =  mainConfigDirectoryPath + "authorizations.xml";
        mainUsersFilePath =  mainConfigDirectoryPath + "users.xml";

        Date date = new Date(System.currentTimeMillis());
        Format formatter = new SimpleDateFormat("yyyyMMdd_hhmmss");
        String dateString = formatter.format(date);

        //Returns true, if any config file is missing
        if (validateMissingConfig(dateString)) {
            return;
        }

        validateWellFormedXmlConfig(dateString);
    }

    private boolean shouldSkipValidation() throws IOException {
        String cleanConf = System.getenv("NIFI_CONF_PV_CLEAN_CONF");
        if ("true".equals(cleanConf)) {
            log.info("NIFI_CONF_PV_CLEAN_CONF set to true, skipping validation");
            return true;
        }

        return false;
    }

    private void validateWellFormedXmlConfig(String dateString) throws ParserConfigurationException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Boolean isAuthorizationsFileValid = checkIfXmlIsValid(mainAuthorizationsFilePath, builder);
        Boolean isUsersFileValid = checkIfXmlIsValid(mainUsersFilePath, builder);

        if(isAuthorizationsFileValid && isUsersFileValid) {
            log.info("Deleting config from restore directory, as main config's are valid");
            deleteRestoreConfig();
            return;
        }

        if (!isAuthorizationsFileValid && !isUsersFileValid) {
            renameFile(mainAuthorizationsFilePath, mainAuthorizationsFilePath + ".bk_corrupted_" + dateString);
            renameFile(mainUsersFilePath, mainUsersFilePath + ".bk_corrupted_" + dateString);
            copyRestoreConfigToMain();
        } else if (!isAuthorizationsFileValid) {
            renameFile(mainAuthorizationsFilePath, mainAuthorizationsFilePath + ".bk_corrupted_" + dateString);
            renameFile(mainUsersFilePath, mainUsersFilePath + ".bk_" + dateString);
            copyRestoreConfigToMain();
        } else {
            renameFile(mainUsersFilePath, mainUsersFilePath + ".bk_corrupted_" + dateString);
            renameFile(mainAuthorizationsFilePath, mainAuthorizationsFilePath + ".bk_" + dateString);
            copyRestoreConfigToMain();
        }
    }

    private boolean validateMissingConfig(String dateString) throws IOException {
        Boolean mainAuthFileExists = new File(mainAuthorizationsFilePath).exists();
        Boolean mainUsersFileExists = new File(mainUsersFilePath).exists();
        if (!mainAuthFileExists && !mainUsersFileExists) {
            copyRestoreConfigToMain();
            return true;
        } else if (!mainAuthFileExists) {
            renameFile(mainUsersFilePath, mainUsersFilePath + ".bk_" + dateString);
            copyRestoreConfigToMain();
            return true;
        } else if (!mainUsersFileExists) {
            renameFile(mainAuthorizationsFilePath, mainAuthorizationsFilePath + ".bk_" + dateString);
            copyRestoreConfigToMain();
            return true;
        }
        return false;
    }

    private boolean checkIfXmlIsValid(String xmlFilePath, DocumentBuilder builder) throws IOException {
        try {
            builder.parse(new InputSource(xmlFilePath));
        } catch (SAXException ex) {
            log.error("Error when parsing xml: " + xmlFilePath, ex);
            return false;
        }
        return true;
    }

    private void deleteRestoreConfig() {
        deleteFile(restoreDirectoryPath + "authorizations.xml");
        deleteFile(restoreDirectoryPath + "users.xml");
    }
    private void deleteFile(String filePath) {
        File fileToDelete = new File(filePath);
        log.info("Deleting file {} ", filePath);
        fileToDelete.delete();
    }

    private void renameFile(String sourcePath, String destPath){
        File oldFile = new File(sourcePath);
        File newFile = new File(destPath);
        log.info("Renaming file {} to {} ", sourcePath, destPath);
        oldFile.renameTo(newFile);
    }

    private void copyRestoreConfigToMain() throws IOException {
        File srcAuth = new File(restoreDirectoryPath + "authorizations.xml");
        File srcUser = new File(restoreDirectoryPath + "users.xml");
        if(srcAuth.exists() && srcUser.exists()) {
            File destAuth = new File(mainAuthorizationsFilePath);
            log.info("Copying file {} to {} ", srcAuth.getAbsolutePath(), destAuth.getAbsolutePath());
            Files.copy(srcAuth.toPath(),destAuth.toPath(), StandardCopyOption.REPLACE_EXISTING);

            File destUser = new File(mainUsersFilePath);
            log.info("Copying file {} to {} ", srcUser.getAbsolutePath(), destUser.getAbsolutePath());
            Files.copy(srcUser.toPath(),destUser.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
