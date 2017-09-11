package io.fabric8.forge.generator.github;

import javax.inject.Inject;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.forge.addon.projects.ProjectFactory;
import org.jboss.forge.addon.shell.test.ShellTest;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

//import org.jboss.forge.arquillian.archive.ForgeArchive;

@RunWith(Arquillian.class)
public class GitHubImportPickOrganisationStepTest {

    @Inject
    private ShellTest shellTest;

    @Inject
    protected ProjectFactory projectFactory;

    @Test
    public void testSomething() throws Exception {
        Assert.assertNotNull(shellTest);
        Assert.assertNotNull(projectFactory);
    }
}
