package com.open.spring.mvc.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.open.spring.mvc.toolchain.ToolchainStationRules.CheckResult;
import com.open.spring.mvc.toolchain.ToolchainStationRules.Evidence;

/** Output samples from macOS, Windows and Linux must all pass. */
class ToolchainStationRulesTest {
    private final ToolchainStationRules stations = new ToolchainStationRules("opencs/pages");

    private boolean passes(String stationId, Evidence... evidence) {
        List<CheckResult> results = stations.evaluate(stationId, List.of(evidence));
        return !results.isEmpty() && results.stream().allMatch(CheckResult::passed);
    }

    @Test
    void terminalGateAcceptsPagesClonedIntoOpencsOnEveryOs() {
        assertTrue(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/Users/hope/opencs/pages"),
                new Evidence("pages-clone", 0, "/Users/hope/opencs/pages")));
        // Git for Windows prints the top level with forward slashes
        assertTrue(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "C:\\Users\\Hope Doe\\opencs\\pages"),
                new Evidence("pages-clone", 0, "C:/Users/Hope Doe/opencs/pages")));
        assertTrue(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/home/hope/opencs/pages"),
                new Evidence("pages-clone", 0, "/home/hope/opencs/pages/\n")));
    }

    @Test
    void terminalGateNoLongerAsksForToolchainTrailFolder() {
        // The old directions made a toolchain-trail folder; the current ones clone pages into opencs.
        assertFalse(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/home/hope"),
                new Evidence("trail-folder", 0, "/home/hope/toolchain-trail")));
        assertFalse(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/home/hope/pages"),
                new Evidence("pages-clone", 0, "/home/hope/pages")));
        assertFalse(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/home/hope/opencs/pages"),
                new Evidence("pages-clone", 128, "fatal: not a git repository")));
    }

    @Test
    void compilerForgeAcceptsVersionOutputs() {
        assertTrue(passes("compiler-canyon-forge",
                new Evidence("python", 0, "Python 3.12.4"),
                new Evidence("pip", 0, "pip 24.0 from C:\\Python312\\Lib\\site-packages\\pip (python 3.12)"),
                new Evidence("ruby", 0, "ruby 3.3.4 (2024-07-09 revision be1089c8ec) [x64-mingw-ucrt]"),
                new Evidence("bundler", 0, "Bundler version 2.5.16"),
                new Evidence("rubygems", 0, "3.5.16")));
        assertTrue(passes("compiler-canyon-forge",
                new Evidence("python", 0, "Python 3.13.0"),
                new Evidence("pip", 0, "pip 24.2 from /opt/homebrew/lib/python3.13/site-packages/pip (python 3.13)"),
                new Evidence("ruby", 0, "ruby 3.4.1 (2024-12-25 revision 48d4efcb85) +PRISM [arm64-darwin24]"),
                new Evidence("bundler", 0, "4.0.1"),
                new Evidence("rubygems", 0, "3.6.2")));
        assertFalse(passes("compiler-canyon-forge",
                new Evidence("python", 0, "Python 2.7.18")));
    }

    @Test
    void gitVillageRejectsPlaceholderEmail() {
        assertFalse(passes("git-village-hall",
                new Evidence("git", 0, "git version 2.43.0"),
                new Evidence("git-name", 0, "Hope"),
                new Evidence("git-email", 0, "you@example.com")));
    }

    @Test
    void gatewayAndSummitAcceptGitOutput() {
        assertTrue(passes("github-gateway-arch",
                new Evidence("github-remote", 0, "origin\thttps://github.com/hope/portfolio.git (fetch)"),
                new Evidence("remote-branch", 0, "3f786850e387550fdab836ed7e6dc881de23001b\trefs/heads/main")));
        assertTrue(passes("build-bridge",
                new Evidence("bundle-check", 0, "The Gemfile's dependencies are satisfied")));
    }

    @Test
    void summitChecksPagesAndToolsWithoutCommitOrPush() {
        Evidence[] setup = {
                new Evidence("working-folder", 0, "/home/hope/opencs/pages"),
                new Evidence("pages-clone", 0, "/home/hope/opencs/pages"),
                new Evidence("python", 0, "Python 3.12.3"),
                new Evidence("pip", 0, "pip 24.0 from /usr/lib/python3/dist-packages/pip (python 3.12)"),
                new Evidence("ruby", 0, "ruby 3.3.0 (2023-12-25 revision 5124f9ac75) [x86_64-linux]"),
                new Evidence("bundler", 0, "Bundler version 2.5.4"),
                new Evidence("rubygems", 0, "3.5.3"),
                new Evidence("editor", 0, "1.94.2"),
                new Evidence("git", 0, "git version 2.43.0"),
                new Evidence("git-name", 0, "Hope"),
                new Evidence("git-email", 0, "h***@gmail.com")};
        assertTrue(passes("integration-summit", setup));

        List<String> ids = stations.evaluate("integration-summit", List.of(setup)).stream().map(CheckResult::id).toList();
        assertFalse(ids.contains("recent-commit"));
        assertFalse(ids.contains("push-dry-run"));

        // A missing tool still fails the summit
        Evidence[] noRuby = Arrays.stream(setup).filter(item -> !item.id().equals("ruby")).toArray(Evidence[]::new);
        assertFalse(passes("integration-summit", noRuby));
        // So does an old agent that still sends only a commit and a push dry run
        assertFalse(passes("integration-summit",
                new Evidence("recent-commit", 0, "a1b2c3d Add toolchain notes"),
                new Evidence("push-dry-run", 0, "Everything up-to-date")));
    }

    @Test
    void nonZeroExitCodeFailsEvenWithMatchingOutput() {
        assertFalse(passes("editor-isle-tower", new Evidence("editor", 1, "1.94.2")));
    }
}
