package org.zalando.logbook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.zalando.logbook.HeaderFilters.replaceCookies;

final class CookieHeaderFilterTest {

    @Test
    void parsesSetCookieHeader() {
        final HeaderFilter unit = replaceCookies("sessionToken"::equals, "XXX");

        final Map<String, List<String>> before = singletonMap(
                "Set-Cookie", asList(
                        "theme=light",
                        "sessionToken=abc123; Path=/; Expires=Wed, 09 Jun 2021 10:18:14 GMT"));

        final Map<String, List<String>> after = unit.filter(before);

        assertThat(after, hasEntry("Set-Cookie", asList(
                "theme=light",
                "sessionToken=XXX; Path=/; Expires=Wed, 09 Jun 2021 10:18:14 GMT"
        )));
    }

    @Test
    void parsesCookieHeader() {
        final HeaderFilter unit = replaceCookies("sessionToken"::equals, "XXX");

        final Map<String, List<String>> before = singletonMap(
                "Cookie", singletonList("theme=light; sessionToken=abc123"));

        final Map<String, List<String>> after = unit.filter(before);

        assertThat(after, hasEntry(
                "Cookie", singletonList("theme=light; sessionToken=XXX")));
    }

    @Test
    void ignoresEmptyCookieHeader() {
        final HeaderFilter unit = replaceCookies("sessionToken"::equals, "XXX");

        final Map<String, List<String>> before = singletonMap(
                "Cookie", singletonList(""));

        final Map<String, List<String>> after = unit.filter(before);

        assertThat(after, hasEntry(
                "Cookie", singletonList("")));
    }

    @Test
    void ignoresEmptySetCookieHeader() {
        final HeaderFilter unit = replaceCookies("sessionToken"::equals, "XXX");

        final Map<String, List<String>> before = singletonMap(
                "Set-Cookie", singletonList(""));

        final Map<String, List<String>> after = unit.filter(before);

        assertThat(after, hasEntry(
                "Set-Cookie", singletonList("")));
    }

    @Test
    void ignoresNoCookieOrSetCookiesHeader() {
        final HeaderFilter unit = replaceCookies("sessionToken"::equals, "XXX");
        final Map<String, List<String>> after = unit.filter(emptyMap());
        assertThat(after, is(emptyMap()));
    }

}
