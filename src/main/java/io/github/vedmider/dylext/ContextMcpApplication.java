package io.github.vedmider.dylext;

import io.github.vedmider.dylext.adapter.in.mcp.StdioGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ContextMcpApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ContextMcpApplication.class);
        if (isStdio(args)) {
            StdioGuard.claimStdout();
            app.setDefaultProperties(StdioGuard.stdioDefaults());
        }
        app.run(args);
    }

    private static boolean isStdio(String[] args) {
        if (Boolean.getBoolean("dylext.mcp.stdio")) {
            return true;
        }
        for (String a : args) {
            if (a.equals("--dylext.mcp.stdio=true")) {
                return true;
            }
        }
        return false;
    }
}
