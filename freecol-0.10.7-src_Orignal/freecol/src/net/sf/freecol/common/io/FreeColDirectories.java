package net.sf.freecol.common.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import javax.swing.filechooser.FileSystemView;




public class FreeColDirectories {

    public static final String COPYRIGHT = "Copyright (C) 2003-2012 The FreeCol Team";

    public static final String LICENSE = "http://www.gnu.org/licenses/gpl.html";

    public static final String REVISION = "$Revision: 2763 $";

    private static File saveDirectory;

    /** Directory containing automatically created save games.
     *  At program start, the path of this directory is based on the path
     *  where to store regular save games. If the value of saveGame is
     *  changed by the user during the game, then the value of
     *  autoSaveDirectory will not be effected.
     */
    private static File autoSaveDirectory;

    private static File mainUserDirectory = null;

    private static File tcUserDirectory;

    private static File userModsDirectory;

    private static String tc = FreeColDirectories.DEFAULT_TC;

    private static File savegameFile = null;

    public static final String DEFAULT_TC = "freecol";

    private static String dataFolder = "data";

    private static File clientOptionsFile = null;

    private static final String HIGH_SCORE_FILE = "HighScores.xml";

    private static final String DIRECTORY = "rules";

    private static final String STRINGS_DIRECTORY = "strings";

    /**
     * Creates a freecol dir for the current user.
     *
     * The directory is created within the current user's
     * home directory. This directory will be called "freecol"
     * and underneath that directory a "save" directory will
     * be created.
     *
     * For MacOS X the Library/FreeCol is used
     * (which is the standard path for application related files).
     *
     * For os.name beginning with "Windows" JFileChooser() is used to
     * find the path to "My Documents" (or localized equivalent)
     */
    public static void createAndSetDirectories() {
        // TODO: The location of the save directory should be determined by the installer.;
    
        String freeColDirectoryName = "/".equals(System.getProperty("file.separator")) ?
                ".freecol" : "freecol";
    
        File userHome = FileSystemView.getFileSystemView().getDefaultDirectory();
    
        // Checks for OS specific paths, however if the old {home}/.freecol exists
        // that overrides OS-specifics for backwards compatibility.
        // TODO: remove compatibility code
        if (System.getProperty("os.name").equals("Mac OS X")) {
            // We are running on a Mac and should use {home}/Library/FreeCol
            if (!new File(userHome, freeColDirectoryName).isDirectory()) {
                userHome = new File(userHome, "Library");
                freeColDirectoryName = "FreeCol";
            }
        } else if (System.getProperty("os.name").startsWith("Windows")) {
            // We are running on Windows and should use "My Documents" (or localized equivalent)
            if (!new File(userHome, freeColDirectoryName).isDirectory()) {
                freeColDirectoryName = "FreeCol";
            }
        }
    
        if (FreeColDirectories.mainUserDirectory == null) {
            FreeColDirectories.setMainUserDirectory(new File(userHome, freeColDirectoryName));
        }
        if (!FreeColDirectories.insistDirectory(FreeColDirectories.mainUserDirectory)) return;
        if (FreeColDirectories.saveDirectory == null) {
            FreeColDirectories.saveDirectory = new File(FreeColDirectories.getMainUserDirectory(), "save");
        }
        if (!FreeColDirectories.insistDirectory(FreeColDirectories.saveDirectory)) FreeColDirectories.saveDirectory = null;
    
        FreeColDirectories.autoSaveDirectory = new File(FreeColDirectories.saveDirectory, "autosave");
        if (!FreeColDirectories.insistDirectory(FreeColDirectories.autoSaveDirectory)) FreeColDirectories.autoSaveDirectory = null;
    
        FreeColDirectories.tcUserDirectory = new File(FreeColDirectories.getMainUserDirectory(), FreeColDirectories.getTc());
        if (!FreeColDirectories.insistDirectory(FreeColDirectories.tcUserDirectory)) FreeColDirectories.tcUserDirectory = null;
    
        FreeColDirectories.userModsDirectory = new File(FreeColDirectories.getMainUserDirectory(), "mods");
        if (!FreeColDirectories.insistDirectory(FreeColDirectories.userModsDirectory)) FreeColDirectories.userModsDirectory = null;
    
        if (FreeColDirectories.clientOptionsFile == null) {
            FreeColDirectories.clientOptionsFile = (FreeColDirectories.tcUserDirectory == null) ? null
                : new File(FreeColDirectories.tcUserDirectory, "options.xml");
        }
    }

    /**
     * Returns the directory where the autogenerated savegames
     * should be put.
     *
     * @return The directory.
     */
    public static File getAutosaveDirectory() {
        return autoSaveDirectory;
    }

    public static File getBaseDirectory() {
        return new File(getDataDirectory(), "base");
    }

    /**
     * Returns the file containing the client options.
     * @return The file.
     */
    public static File getClientOptionsFile() {
        return clientOptionsFile;
    }

    /**
     * Returns the data directory.
     * @return The directory where the data files are located.
     */
    public static File getDataDirectory() {
        if (FreeColDirectories.dataFolder.equals("")) {
            return new File("data");
        } else {
            return new File(FreeColDirectories.dataFolder);
        }
    }

    public static File getHighScoreFile() {
        return new File(getDataDirectory(), FreeColDirectories.HIGH_SCORE_FILE);
    }

    /**
     * Returns the directory containing language property files.
     *
     * @return a <code>File</code> value
     */
    public static File getI18nDirectory() {
        return new File(getDataDirectory(), FreeColDirectories.STRINGS_DIRECTORY);
    }

    public static File getMainUserDirectory() {
        return mainUserDirectory;
    }

    public static File getMapsDirectory() {
        return new File(getDataDirectory(), "maps");
    }

    /**
     * Returns the directory for saving options.
     *
     * @return The directory.
     */
    public static File getOptionsDirectory() {
        return tcUserDirectory;
    }

    public static File getRulesClassicDirectory() {
        return new File(getDataDirectory(), "rules/classic");
    }

    public static File getRulesDirectory() {
        return new File(getDataDirectory(), FreeColDirectories.DIRECTORY);
    }

    /**
     * Returns the directory where the savegames should be put.
     * @return The directory where the savegames should be put.
     */
    public static File getSaveDirectory() {
        return saveDirectory;
    }

    public static File getSavegameFile() {
        return savegameFile;
    }

    /**
     * Gets the mods directory.
     *
     * @return The directory where the standard mods are located.
     */
    public static File getStandardModsDirectory() {
        return new File(FreeColDirectories.getDataDirectory(), "mods");
    }

    public static String getTc() {
        return tc;
    }

    /**
     * Gets the user mods directory.
     *
     * @return The directory where user mods are located, or null if none.
     */
    public static File getUserModsDirectory() {
        return userModsDirectory;
    }

    /**
     * Try to make a directory.
     *
     * @param file A <code>File</code> specifying where to make the directory.
     * @return True if the directory is there after the call.
     */
    public static boolean insistDirectory(File file) {
        if (file.exists()) {
            if (file.isDirectory()) return true;
            System.out.println("Could not create directory " + file.getName()
                + " under " + file.getParentFile().getName()
                + " because a non-directory with that name is already there.");
            return false;
        }
        return file.mkdir();
    }

    public static void setClientOptionsFile(File file) {
        FreeColDirectories.clientOptionsFile = file;
        
    }

    public static void setDataFolder(String dataFolder) {
        FreeColDirectories.dataFolder = dataFolder;
    }

    public static void setMainUserDirectory(File mainUserDirectory) {
        FreeColDirectories.mainUserDirectory = mainUserDirectory;
    }

    /**
     * Set the directory where the savegames should be put.
     * @param saveDirectory a <code>File</code> value for the savegame directory
     */
    public static void setSaveDirectory(File saveDirectory) {
        FreeColDirectories.saveDirectory = saveDirectory;
    }

    public static void setSavegameFile(File savegameFile) {
        FreeColDirectories.savegameFile = savegameFile;
    }

    public static void setSaveGameFile(String name) {
        setSavegameFile(new File(name));
        if (!getSavegameFile().exists() || !getSavegameFile().isFile()) {
            setSavegameFile(new File(getSaveDirectory(), name));
            if (!getSavegameFile().exists() || !getSavegameFile().isFile()) {
                System.out.println("Could not find savegame file: " + name);
                System.exit(1);
            }
        } else {
            setSaveDirectory(getSavegameFile().getParentFile());
        }
    }

    public static void setTc(String tc) {
        FreeColDirectories.tc = tc;
    }

}
