package com.placement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/reports/*")
public class AggregateReportServlet extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Map<String, ReportDefinition>> REPORTS =
            createReports();

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        ReportDefinition definition;
        String filter;
        try {
            definition = getReport(request);
            filter = readFilter(request);
        } catch (IllegalArgumentException e) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", e.getMessage()));
            return;
        }

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(definition.sql())) {
            if (filter == null) {
                statement.setNull(1, Types.VARCHAR);
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(1, filter);
                statement.setString(2, filter);
            }

            try (ResultSet results = statement.executeQuery()) {
                ResultSetMetaData metadata = results.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    columns.add(metadata.getColumnLabel(index));
                }

                List<List<String>> rows = new ArrayList<>();
                while (results.next()) {
                    List<String> row = new ArrayList<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.add(results.getString(index));
                    }
                    rows.add(row);
                }

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("columns", columns);
                output.put("rows", rows);
                output.put("message", rows.isEmpty()
                        ? "No records matched this aggregate report."
                        : "");
                writeJson(response, HttpServletResponse.SC_OK, output);
            }
        } catch (SQLException e) {
            getServletContext().log(
                    "Aggregate report failed for " + definition.table()
                            + ".", e);
            writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    Map.of("error",
                            "Oracle could not run this aggregate report: "
                                    + e.getMessage()));
        }
    }

    private static ReportDefinition getReport(
            HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Choose a valid table and aggregate report.");
        }

        String[] parts = path.substring(1).split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Choose a valid table and aggregate report.");
        }

        Map<String, ReportDefinition> tableReports = REPORTS.get(parts[0]);
        if (tableReports == null) {
            throw new IllegalArgumentException(
                    "Aggregate reports are not available for that table.");
        }
        ReportDefinition definition = tableReports.get(parts[1]);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "That aggregate report is not supported.");
        }
        return definition;
    }

    private static String readFilter(HttpServletRequest request)
            throws IOException {
        Map<String, Object> input;
        try {
            input = JSON.readValue(request.getInputStream(),
                    new TypeReference<>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "The report filter request is invalid.");
        }

        Object value = input.get("filter");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    "The report filter must be text.");
        }
        String filter = text.trim();
        if (filter.isEmpty()) {
            return null;
        }
        if (filter.length() > 100) {
            throw new IllegalArgumentException(
                    "The report filter cannot exceed 100 characters.");
        }
        return filter;
    }

    private static void writeJson(HttpServletResponse response, int status,
                                  Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), body);
    }

    private static ReportDefinition report(String table, String sql) {
        return new ReportDefinition(table, sql);
    }

    private static Map<String, Map<String, ReportDefinition>> createReports() {
        Map<String, Map<String, ReportDefinition>> reports =
                new LinkedHashMap<>();
        reports.put("companies", Map.of(
                "byIndustry", report("COMPANY", """
                        SELECT INDUSTRY_TYPE, COUNT(*) AS COMPANY_COUNT,
                               MIN(COMPANY_ID) AS MIN_COMPANY_ID,
                               MAX(COMPANY_ID) AS MAX_COMPANY_ID
                        FROM COMPANY
                        WHERE (? IS NULL
                               OR UPPER(INDUSTRY_TYPE) = UPPER(?))
                        GROUP BY INDUSTRY_TYPE
                        ORDER BY COMPANY_COUNT DESC, INDUSTRY_TYPE
                        """),
                "byLocation", report("COMPANY", """
                        SELECT LOCATION, COUNT(*) AS COMPANY_COUNT,
                               COUNT(DISTINCT INDUSTRY_TYPE)
                                   AS INDUSTRY_COUNT
                        FROM COMPANY
                        WHERE (? IS NULL OR UPPER(LOCATION) = UPPER(?))
                        GROUP BY LOCATION
                        ORDER BY COMPANY_COUNT DESC, LOCATION
                        """),
                "jobsByCompany", report("COMPANY", """
                        SELECT c.COMPANY_ID, c.COMPANY_NAME,
                               COUNT(j.JOB_ID) AS JOB_COUNT,
                               NVL(SUM(j.NUMBER_OF_VACANCIES), 0)
                                   AS TOTAL_VACANCIES,
                               ROUND(AVG(j.ANNUAL_PACKAGE), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(j.ANNUAL_PACKAGE) AS MIN_PACKAGE_LPA,
                               MAX(j.ANNUAL_PACKAGE) AS MAX_PACKAGE_LPA
                        FROM COMPANY c
                        LEFT JOIN JOB_POSTING j
                            ON j.COMPANY_ID = c.COMPANY_ID
                        WHERE (? IS NULL
                               OR TO_CHAR(c.COMPANY_ID) = ?)
                        GROUP BY c.COMPANY_ID, c.COMPANY_NAME
                        ORDER BY JOB_COUNT DESC, c.COMPANY_NAME
                        """)));
        reports.put("jobs", Map.of(
                "byStatus", report("JOB_POSTING", """
                        SELECT JOB_STATUS, COUNT(*) AS JOB_COUNT,
                               SUM(NUMBER_OF_VACANCIES) AS TOTAL_VACANCIES,
                               ROUND(AVG(ANNUAL_PACKAGE), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE) AS MAX_PACKAGE_LPA
                        FROM JOB_POSTING
                        WHERE (? IS NULL OR UPPER(JOB_STATUS) = UPPER(?))
                        GROUP BY JOB_STATUS
                        ORDER BY JOB_COUNT DESC, JOB_STATUS
                        """),
                "byType", report("JOB_POSTING", """
                        SELECT JOB_TYPE, COUNT(*) AS JOB_COUNT,
                               SUM(NUMBER_OF_VACANCIES) AS TOTAL_VACANCIES,
                               ROUND(AVG(ANNUAL_PACKAGE), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE) AS MAX_PACKAGE_LPA
                        FROM JOB_POSTING
                        WHERE (? IS NULL OR UPPER(JOB_TYPE) = UPPER(?))
                        GROUP BY JOB_TYPE
                        ORDER BY JOB_COUNT DESC, JOB_TYPE
                        """),
                "byCompany", report("JOB_POSTING", """
                        SELECT c.COMPANY_ID, c.COMPANY_NAME,
                               COUNT(j.JOB_ID) AS JOB_COUNT,
                               SUM(j.NUMBER_OF_VACANCIES) AS TOTAL_VACANCIES,
                               ROUND(AVG(j.ANNUAL_PACKAGE), 2)
                                   AS AVG_PACKAGE_LPA
                        FROM JOB_POSTING j
                        JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        WHERE (? IS NULL
                               OR TO_CHAR(c.COMPANY_ID) = ?)
                        GROUP BY c.COMPANY_ID, c.COMPANY_NAME
                        ORDER BY JOB_COUNT DESC, c.COMPANY_NAME
                        """)));
        reports.put("students", Map.of(
                "byDepartment", report("STUDENT", """
                        SELECT DEPARTMENT, COUNT(*) AS STUDENT_COUNT,
                               ROUND(AVG(CGPA), 2) AS AVG_CGPA,
                               MIN(CGPA) AS MIN_CGPA,
                               MAX(CGPA) AS MAX_CGPA
                        FROM STUDENT
                        WHERE (? IS NULL OR UPPER(DEPARTMENT) = UPPER(?))
                        GROUP BY DEPARTMENT
                        ORDER BY STUDENT_COUNT DESC, DEPARTMENT
                        """),
                "byPlacementStatus", report("STUDENT", """
                        SELECT PLACEMENT_STATUS,
                               COUNT(*) AS STUDENT_COUNT,
                               ROUND(AVG(CGPA), 2) AS AVG_CGPA,
                               MIN(CGPA) AS MIN_CGPA,
                               MAX(CGPA) AS MAX_CGPA
                        FROM STUDENT
                        WHERE (? IS NULL
                               OR UPPER(PLACEMENT_STATUS) = UPPER(?))
                        GROUP BY PLACEMENT_STATUS
                        ORDER BY STUDENT_COUNT DESC, PLACEMENT_STATUS
                        """),
                "byGraduationYear", report("STUDENT", """
                        SELECT GRADUATION_YEAR, COUNT(*) AS STUDENT_COUNT,
                               ROUND(AVG(CGPA), 2) AS AVG_CGPA,
                               MIN(CGPA) AS MIN_CGPA,
                               MAX(CGPA) AS MAX_CGPA
                        FROM STUDENT
                        WHERE (? IS NULL
                               OR TO_CHAR(GRADUATION_YEAR) = ?)
                        GROUP BY GRADUATION_YEAR
                        ORDER BY GRADUATION_YEAR
                        """)));
        reports.put("applications", Map.of(
                "byStatus", report("APPLICATION", """
                        SELECT APPLICATION_STATUS,
                               COUNT(*) AS APPLICATION_COUNT,
                               COUNT(DISTINCT STUDENT_ID) AS STUDENT_COUNT,
                               COUNT(DISTINCT JOB_ID) AS JOB_COUNT,
                               MIN(APPLICATION_DATE) AS FIRST_APPLICATION,
                               MAX(APPLICATION_DATE) AS LAST_APPLICATION
                        FROM APPLICATION
                        WHERE (? IS NULL
                               OR UPPER(APPLICATION_STATUS) = UPPER(?))
                        GROUP BY APPLICATION_STATUS
                        ORDER BY APPLICATION_COUNT DESC, APPLICATION_STATUS
                        """),
                "byJob", report("APPLICATION", """
                        SELECT j.JOB_ID, j.JOB_TITLE, c.COMPANY_NAME,
                               COUNT(a.APPLICATION_ID) AS APPLICATION_COUNT,
                               COUNT(DISTINCT a.STUDENT_ID) AS STUDENT_COUNT,
                               MIN(a.APPLICATION_DATE) AS FIRST_APPLICATION,
                               MAX(a.APPLICATION_DATE) AS LAST_APPLICATION
                        FROM APPLICATION a
                        JOIN JOB_POSTING j ON j.JOB_ID = a.JOB_ID
                        JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        WHERE (? IS NULL OR TO_CHAR(j.JOB_ID) = ?)
                        GROUP BY j.JOB_ID, j.JOB_TITLE, c.COMPANY_NAME
                        ORDER BY APPLICATION_COUNT DESC, j.JOB_TITLE
                        """),
                "byStudent", report("APPLICATION", """
                        SELECT s.STUDENT_ID, s.FULL_NAME,
                               COUNT(a.APPLICATION_ID) AS APPLICATION_COUNT,
                               COUNT(DISTINCT a.JOB_ID) AS JOB_COUNT,
                               MIN(a.APPLICATION_DATE) AS FIRST_APPLICATION,
                               MAX(a.APPLICATION_DATE) AS LAST_APPLICATION
                        FROM APPLICATION a
                        JOIN STUDENT s ON s.STUDENT_ID = a.STUDENT_ID
                        WHERE (? IS NULL OR TO_CHAR(s.STUDENT_ID) = ?)
                        GROUP BY s.STUDENT_ID, s.FULL_NAME
                        ORDER BY APPLICATION_COUNT DESC, s.FULL_NAME
                        """)));
        reports.put("interviews", Map.of(
                "byStatus", report("INTERVIEW", """
                        SELECT INTERVIEW_STATUS,
                               COUNT(*) AS INTERVIEW_COUNT,
                               ROUND(AVG(INTERVIEW_ROUND), 2) AS AVG_ROUND,
                               MIN(INTERVIEW_ROUND) AS MIN_ROUND,
                               MAX(INTERVIEW_ROUND) AS MAX_ROUND
                        FROM INTERVIEW
                        WHERE (? IS NULL
                               OR UPPER(INTERVIEW_STATUS) = UPPER(?))
                        GROUP BY INTERVIEW_STATUS
                        ORDER BY INTERVIEW_COUNT DESC, INTERVIEW_STATUS
                        """),
                "byMode", report("INTERVIEW", """
                        SELECT INTERVIEW_MODE,
                               COUNT(*) AS INTERVIEW_COUNT,
                               ROUND(AVG(INTERVIEW_ROUND), 2) AS AVG_ROUND,
                               MIN(INTERVIEW_DATE) AS FIRST_INTERVIEW,
                               MAX(INTERVIEW_DATE) AS LAST_INTERVIEW
                        FROM INTERVIEW
                        WHERE (? IS NULL
                               OR UPPER(INTERVIEW_MODE) = UPPER(?))
                        GROUP BY INTERVIEW_MODE
                        ORDER BY INTERVIEW_COUNT DESC, INTERVIEW_MODE
                        """),
                "byApplication", report("INTERVIEW", """
                        SELECT APPLICATION_ID,
                               COUNT(*) AS INTERVIEW_COUNT,
                               COUNT(DISTINCT INTERVIEW_ROUND) AS ROUND_COUNT,
                               MIN(INTERVIEW_DATE) AS FIRST_INTERVIEW,
                               MAX(INTERVIEW_DATE) AS LAST_INTERVIEW
                        FROM INTERVIEW
                        WHERE (? IS NULL
                               OR TO_CHAR(APPLICATION_ID) = ?)
                        GROUP BY APPLICATION_ID
                        ORDER BY INTERVIEW_COUNT DESC, APPLICATION_ID
                        """)));
        reports.put("offers", Map.of(
                "byStatus", report("OFFER", """
                        SELECT OFFER_STATUS, COUNT(*) AS OFFER_COUNT,
                               ROUND(SUM(ANNUAL_PACKAGE_LPA), 2)
                                   AS TOTAL_PACKAGE_LPA,
                               ROUND(AVG(ANNUAL_PACKAGE_LPA), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE_LPA) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE_LPA) AS MAX_PACKAGE_LPA
                        FROM OFFER
                        WHERE (? IS NULL OR UPPER(OFFER_STATUS) = UPPER(?))
                        GROUP BY OFFER_STATUS
                        ORDER BY OFFER_COUNT DESC, OFFER_STATUS
                        """),
                "byType", report("OFFER", """
                        SELECT OFFER_TYPE, COUNT(*) AS OFFER_COUNT,
                               ROUND(AVG(ANNUAL_PACKAGE_LPA), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE_LPA) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE_LPA) AS MAX_PACKAGE_LPA
                        FROM OFFER
                        WHERE (? IS NULL OR UPPER(OFFER_TYPE) = UPPER(?))
                        GROUP BY OFFER_TYPE
                        ORDER BY OFFER_COUNT DESC, OFFER_TYPE
                        """),
                "byCompany", report("OFFER", """
                        SELECT COMPANY_NAME, COUNT(*) AS OFFER_COUNT,
                               ROUND(SUM(ANNUAL_PACKAGE_LPA), 2)
                                   AS TOTAL_PACKAGE_LPA,
                               ROUND(AVG(ANNUAL_PACKAGE_LPA), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE_LPA) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE_LPA) AS MAX_PACKAGE_LPA
                        FROM OFFER
                        WHERE (? IS NULL OR UPPER(COMPANY_NAME) = UPPER(?))
                        GROUP BY COMPANY_NAME
                        ORDER BY OFFER_COUNT DESC, COMPANY_NAME
                        """)));
        return Map.copyOf(reports);
    }

    private record ReportDefinition(String table, String sql) { }
}
