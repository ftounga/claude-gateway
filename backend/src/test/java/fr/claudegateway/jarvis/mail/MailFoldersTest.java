package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** F-242 / SF-242-01 (D3) — découverte des dossiers par SPECIAL-USE, noms localisés, repli sans attribut. */
class MailFoldersTest {

    private static MailFolders.FolderInfo f(String name, String... attributes) {
        return new MailFolders.FolderInfo(name, List.of(attributes));
    }

    @Test
    void frenchGmailFoldersAreFoundByAttributeNotByName() {
        MailFolders folders = MailFolders.discover(List.of(
                f("INBOX", "\\HasNoChildren"),
                f("[Gmail]", "\\Noselect", "\\HasChildren"),
                f("[Gmail]/Brouillons", "\\HasNoChildren", "\\Drafts"),
                f("[Gmail]/Corbeille", "\\HasNoChildren", "\\Trash"),
                f("[Gmail]/Tous les messages", "\\All", "\\HasNoChildren"),
                f("[Gmail]/Messages envoyés", "\\HasNoChildren", "\\Sent"),
                // Un dossier nommé « Trash » sans attribut ne doit PAS l'emporter sur l'attribut.
                f("Trash", "\\HasNoChildren")));

        assertThat(folders.drafts()).isEqualTo("[Gmail]/Brouillons");
        assertThat(folders.trash()).isEqualTo("[Gmail]/Corbeille");
        assertThat(folders.all()).isEqualTo("[Gmail]/Tous les messages");
        assertThat(folders.sent()).isEqualTo("[Gmail]/Messages envoyés");
    }

    @Test
    void attributesAreCaseInsensitive() {
        MailFolders folders = MailFolders.discover(List.of(f("Papierkorb", "\\TRASH"), f("Entwürfe", "\\drafts")));
        assertThat(folders.trash()).isEqualTo("Papierkorb");
        assertThat(folders.drafts()).isEqualTo("Entwürfe");
        assertThat(folders.all()).isNull();
    }

    @Test
    void englishNamesOnlyWhenNoSpecialUseIsAnnounced() {
        MailFolders folders = MailFolders.discover(List.of(f("INBOX"), f("Drafts"), f("Trash"), f("Archive"),
                f("Sent")));
        assertThat(folders).isEqualTo(new MailFolders("Drafts", "Trash", "Archive", "Sent"));
    }

    @Test
    void nothingListedGivesNone() {
        assertThat(MailFolders.discover(List.of())).isEqualTo(MailFolders.NONE);
        assertThat(MailFolders.discover(null)).isEqualTo(MailFolders.NONE);
    }

    @Test
    void googlePresetByDefault() {
        JarvisMailProperties p = JarvisMailProperties.defaults();
        assertThat(p.imapHost()).isEqualTo("imap.gmail.com");
        assertThat(p.imapPort()).isEqualTo(993);
        assertThat(p.smtpHost()).isEqualTo("smtp.gmail.com");
        assertThat(p.smtpPort()).isEqualTo(465);
        assertThat(p.isSsl()).isTrue();
        assertThat(p.maxAccounts()).isEqualTo(2);
        assertThat(p.pollEvery()).hasMinutes(5);
        assertThat(p.isEnabled()).isTrue();
    }

    @Test
    void inputsAreNormalized() {
        assertThat(JarvisMailAccountService.normalizeAddress("  NTounga@Gmail.com ")).isEqualTo("ntounga@gmail.com");
        assertThat(JarvisMailAccountService.normalizeSecret("abcd efgh ijkl mnop")).isEqualTo("abcdefghijklmnop");
        assertThat(JarvisMailAccountService.normalizeLabel("", "a@gmail.com")).isEqualTo("Perso");
        assertThat(JarvisMailAccountService.normalizeLabel(null, "a@ng-itconsulting.com")).isEqualTo("Pro");
    }
}
