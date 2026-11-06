package uk.gov.hmcts.demo.civil.document;

import org.springframework.stereotype.Component;

@Component
public class DemoVirusScanner implements VirusScanner {
    @Override
    public void scan(byte[] content) {
        // DEMO ONLY: this is a CLEAN-result stub, not real malware detection.
    }
}
