package it;

import javax.sql.DataSource;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    private final DataSource dataSource;

    public ReportService(DataSource dataSource) {
        this.dataSource = dataSource;
    }
}
