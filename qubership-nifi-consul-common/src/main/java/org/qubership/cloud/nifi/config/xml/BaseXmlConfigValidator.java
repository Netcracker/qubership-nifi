package org.qubership.cloud.nifi.config.xml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;

public class BaseXmlConfigValidator {
    private static final Logger LOG = LoggerFactory.getLogger(BaseXmlConfigValidator.class);

    private String path;
    private String mainConfigDirectoryPath;
    private String restoreDirectoryPath;

    private Path mainAuthorizationsFilePath;
    private Path mainUsersFilePath;

    /**
     * Creates new instance of BaseXmlConfigValidator.
     *
     * @param config configuration containing all required parameters
     */
    public BaseXmlConfigValidator(final XmlConfigValidatorConfig config) {
        this.path = config.defaultPath();
        this.mainConfigDirectoryPath = config.defaultMainConfigDirectoryPath();
        this.restoreDirectoryPath = config.defaultRestoreDirectoryPath();
    }

    /**
     * Validates authorizations.xml and users.xml and restores them from backup, if invalid.
     * @throws IOException
     * @throws ParserConfigurationException
     */
    public void validate() throws IOException, ParserConfigurationException {
        if (shouldSkipValidation()) {
            return;
        }

        LOG.info("Restore directory path: {}", restoreDirectoryPath);

        mainAuthorizationsFilePath = Paths.get(mainConfigDirectoryPath, "authorizations.xml");
        mainUsersFilePath = Paths.get(mainConfigDirectoryPath, "users.xml");

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
            LOG.info("NIFI_CONF_PV_CLEAN_CONF set to true, skipping validation");
            return true;
        }

        return false;
    }

    private Path getBackupFileName(Path mainFile, String postFix) {
        return mainFile.resolveSibling(mainFile.getFileName() + postFix);
    }

    private void validateWellFormedXmlConfig(String dateString) throws ParserConfigurationException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Boolean isAuthorizationsFileValid = checkIfXmlIsValid(mainAuthorizationsFilePath, builder);
        Boolean isUsersFileValid = checkIfXmlIsValid(mainUsersFilePath, builder);

        if (isAuthorizationsFileValid && isUsersFileValid) {
            LOG.info("Deleting config from restore directory, as main config's are valid");
            deleteRestoreConfig();
            return;
        }

        String corruptedPostFix = ".bk_corrupted_" + dateString;
        String backupPostFix = ".bk_" + dateString;

        if (!isAuthorizationsFileValid && !isUsersFileValid) {
            renameFile(mainAuthorizationsFilePath, getBackupFileName(mainAuthorizationsFilePath, corruptedPostFix));
            renameFile(mainUsersFilePath, getBackupFileName(mainUsersFilePath, corruptedPostFix));
            copyRestoreConfigToMain();
        } else if (!isAuthorizationsFileValid) {
            renameFile(mainAuthorizationsFilePath, getBackupFileName(mainAuthorizationsFilePath, corruptedPostFix));
            renameFile(mainUsersFilePath, getBackupFileName(mainUsersFilePath, backupPostFix));
            copyRestoreConfigToMain();
        } else {
            renameFile(mainUsersFilePath, getBackupFileName(mainUsersFilePath, corruptedPostFix));
            renameFile(mainAuthorizationsFilePath, getBackupFileName(mainAuthorizationsFilePath, backupPostFix));
            copyRestoreConfigToMain();
        }
    }

    private boolean validateMissingConfig(String dateString) throws IOException {
        boolean mainAuthFileExists = mainAuthorizationsFilePath.toFile().exists();
        boolean mainUsersFileExists = mainUsersFilePath.toFile().exists();
        String backupPostFix = ".bk_" + dateString;
        if (!mainAuthFileExists && !mainUsersFileExists) {
            copyRestoreConfigToMain();
            return true;
        } else if (!mainAuthFileExists) {
            renameFile(mainUsersFilePath, getBackupFileName(mainUsersFilePath, backupPostFix));
            copyRestoreConfigToMain();
            return true;
        } else if (!mainUsersFileExists) {
            renameFile(mainAuthorizationsFilePath, getBackupFileName(mainAuthorizationsFilePath, backupPostFix));
            copyRestoreConfigToMain();
            return true;
        }
        return false;
    }

    private boolean checkIfXmlIsValid(Path xmlFilePath, DocumentBuilder builder) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(xmlFilePath.toFile()))) {
            builder.parse(new InputSource(in));
        } catch (SAXException ex) {
            LOG.error("Error when parsing xml: " + xmlFilePath, ex);
            return false;
        }
        return true;
    }

    private void deleteRestoreConfig() {
        File restoreAuthXml = Paths.get(restoreDirectoryPath, "authorizations.xml").toFile();
        File restoreUsersXml = Paths.get(restoreDirectoryPath, "users.xml").toFile();
        deleteFile(restoreAuthXml);
        deleteFile(restoreUsersXml);
    }

    private void deleteFile(File fileToDelete) {
        LOG.info("Deleting file {} ", fileToDelete.getPath());
        fileToDelete.delete();
    }

    private void renameFile(Path sourcePath, Path destPath) {
        File oldFile = sourcePath.toFile();
        File newFile = destPath.toFile();
        LOG.info("Renaming file {} to {} ", sourcePath, destPath);
        oldFile.renameTo(newFile);
    }

    private void copyRestoreConfigToMain() throws IOException {
        File srcAuth = Paths.get(restoreDirectoryPath, "authorizations.xml").toFile();
        File srcUser = Paths.get(restoreDirectoryPath, "users.xml").toFile();
        if (srcAuth.exists() && srcUser.exists()) {
            File destAuth = mainAuthorizationsFilePath.toFile();
            LOG.info("Copying authorizations.xml file {} to {} ",
                    srcAuth.getAbsolutePath(), destAuth.getAbsolutePath());
            Files.copy(srcAuth.toPath(), destAuth.toPath(), StandardCopyOption.REPLACE_EXISTING);

            File destUser = mainUsersFilePath.toFile();
            LOG.info("Copying user.xml file {} to {} ", srcUser.getAbsolutePath(), destUser.getAbsolutePath());
            Files.copy(srcUser.toPath(), destUser.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
