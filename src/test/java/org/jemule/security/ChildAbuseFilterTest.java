package org.jemule.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildAbuseFilterTest {

    private final ChildAbuseFilter filter = ChildAbuseFilter.defaults();

    @Test
    void bannedTermsMatchAlone() {
        assertTrue(filter.matches("(pthc) video.avi"));
        assertTrue(filter.matches("new_PTHC_2024.mpg"));
        assertTrue(filter.matches("Hussyfan collection.zip"));
        assertTrue(filter.matches("r@ygold.avi"));
        assertTrue(filter.matches("pedophilia.avi"));
        assertTrue(filter.matches("Child.Porn.avi"));
        assertTrue(filter.matches("best kidporn.mpg"));
    }

    @Test
    void seriesAndStudioMarkersMatchAlone() {
        assertTrue(filter.matches("opva 2016.avi"));
        assertTrue(filter.matches("Bibcam 07.mpg"));
        assertTrue(filter.matches("Kdv 03747 26092012.flv"));
        assertTrue(filter.matches("siberian mouse 1.avi"));
        assertTrue(filter.matches("pedomom 2.avi"));
        assertTrue(filter.matches("педо 1.avi"));
    }

    @Test
    void ambiguousNamesStayAllowed() {
        assertFalse(filter.matches("Lolita (1997).avi"));
        assertFalse(filter.matches("Nina Simone - Feeling Good.mp3"));
        assertFalse(filter.matches("Pedometer Pro.apk"));
        assertFalse(filter.matches("Top 10 y mas.avi"), "an age without a sexual term is not enough");
    }

    @Test
    void ageSuffixesWithoutSpaceAndInSpanish(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keywords.properties");
        Files.writeString(file, "minor = niña\nsexual = forbidden\nageSuffixes = y, años\nmaxMinorAge = 17\n");
        ChildAbuseFilter custom = ChildAbuseFilter.load(file.toString());
        assertTrue(custom.matches("11y forbidden.avi"));
        assertTrue(custom.matches("5 años forbidden.avi"));
        assertTrue(custom.matches("niña forbidden.avi"));
        assertFalse(custom.matches("19y forbidden.avi"));
        assertFalse(custom.matches("2024 y forbidden.avi"), "a year is not an age");
    }

    @Test
    void minorWithSexualTermMatches() {
        assertTrue(filter.matches("BOYFUCK - 6Yo Hot Red Haired Boy Gets Stripped On Floor.avi"));
        assertTrue(filter.matches("12 y.o. nude.jpg"));
        assertTrue(filter.matches("girl 9yrs old naked.avi"));
        assertTrue(filter.matches("preteen sex.mpg"));
        assertTrue(filter.matches("Kids_XXX.wmv"));
        assertTrue(filter.matches("fillette 10 ans baise.avi"));
    }

    @Test
    void legitimateNamesDoNotMatch() {
        assertFalse(filter.matches("Cheech & Chong - 7 - Get Out of My Room (1985).avi"));
        assertFalse(filter.matches("Men - Diego Sans & Ian Frost - Revolt Part 1.mp4"));
        assertFalse(filter.matches("18yo amateur xxx.avi"), "adults are not minors");
        assertFalse(filter.matches("Spy Kids 2 (2002).avi"), "a minor term alone is not enough");
        assertFalse(filter.matches("Sex Education S01E01.mkv"), "a sexual term alone is not enough");
        assertFalse(filter.matches("Baby 2 years old first steps.mp4"));
        assertFalse(filter.matches("Depthcharge.iso"));
        assertFalse(filter.matches("Torpedo - Speedo - Pedometer.mp3"));
        assertFalse(filter.matches("Kidnapped (1960).avi"));
        assertFalse(filter.matches("Windows 2000 SP4.iso"));
        assertFalse(filter.matches(""));
        assertFalse(filter.matches(null));
    }

    @Test
    void keywordFileReplacesTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keywords.properties");
        Files.writeString(file, "banned = forbidden*\nminor = little\nsexual = *dirty\nageSuffixes = yo\nmaxMinorAge = 10\n");
        ChildAbuseFilter custom = ChildAbuseFilter.load(file.toString());
        assertTrue(custom.matches("ForbiddenThing.avi"));
        assertTrue(custom.matches("little superdirty.avi"));
        assertTrue(custom.matches("9yo dirty.avi"));
        assertFalse(custom.matches("11yo dirty.avi"));
        assertFalse(custom.matches("pthc.avi"), "the embedded list is replaced, not extended");
    }

    @Test
    void unreadableKeywordFileFallsBackToTheDefaults(@TempDir Path dir) {
        assertTrue(ChildAbuseFilter.load(dir.resolve("missing.properties").toString()).matches("pthc.avi"));
    }

    @Test
    void emptyListsMatchNothing() {
        assertFalse(ChildAbuseFilter.fromProperties(new Properties()).matches("pthc 6yo xxx.avi"));
    }
}
