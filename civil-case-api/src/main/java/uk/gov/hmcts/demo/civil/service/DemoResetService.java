package uk.gov.hmcts.demo.civil.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.demo.civil.persistence.ClaimRepository;
import uk.gov.hmcts.demo.civil.web.ApiException;
import uk.gov.hmcts.demo.civil.workflow.ClaimWorkflow;

@Service
public class DemoResetService {
    private final boolean enabled;
    private final String token;
    private final String sqlPath;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ClaimWorkflow> workflow;
    private final ClaimRepository claims;
    public DemoResetService(@Value("${demo.reset-enabled:false}") boolean enabled,
            @Value("${demo.reset-token:}") String token,@Value("${demo.reset-sql}") String sqlPath,
            DataSource dataSource,PlatformTransactionManager manager,ObjectProvider<ClaimWorkflow> workflow,
            ClaimRepository claims) {
        this.enabled=enabled; this.token=token; this.sqlPath=sqlPath; this.dataSource=dataSource;
        this.transactions=new TransactionTemplate(manager); this.workflow=workflow; this.claims=claims;
    }

    public synchronized Map<String,Object> reset(String supplied) {
        if (!enabled || token.isBlank() || supplied==null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403,"RESET_FORBIDDEN","Demo reset is disabled or the reset token is incorrect");
        }
        FileSystemResource resource=new FileSystemResource(sqlPath);
        if (!resource.isReadable()) throw new IllegalStateException("Demo reset SQL is not available");
        workflow.ifAvailable(ClaimWorkflow::reset);
        transactions.executeWithoutResult(status -> {
            Connection connection=DataSourceUtils.getConnection(dataSource);
            try {
                ScriptUtils.executeSqlScript(connection,new EncodedResource(resource,StandardCharsets.UTF_8));
            } finally {
                DataSourceUtils.releaseConnection(connection,dataSource);
            }
        });
        // Seed processes only after the replacement business rows have committed.
        workflow.ifAvailable(ClaimWorkflow::seedActiveClaims);
        return Map.of("status","reset","claims",claims.count());
    }
}
