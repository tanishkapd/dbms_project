package com.placement;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/queries/*")
public class SqlQueryServlet extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TABLES = Map.of(
            "companies", "COMPANY",
            "jobs", "JOB_POSTING",
            "students", "STUDENT",
            "applications", "APPLICATION",
            "interviews", "INTERVIEW",
            "offers", "OFFER");
    private static final List<String> QUERY_TYPES = List.of(
            "simple", "aggregate", "complex", "join", "nested", "functions");
    private static final Map<String, Map<String, String>> QUERIES =
            createQueries();

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        QueryDefinition definition;
        try {
            definition = getQuery(request);
        } catch (IllegalArgumentException e) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", e.getMessage()));
            return;
        }

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(definition.sql());
             ResultSet results = statement.executeQuery()) {
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
                    ? "No records were returned for this query."
                    : "");
            writeJson(response, HttpServletResponse.SC_OK, output);
        } catch (SQLException e) {
            getServletContext().log(
                    "SQL query failed for " + definition.table() + ". SQL: "
                            + definition.sql(),
                    e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Oracle could not execute this query: "
                    + e.getMessage());
            writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    error);
        }
    }

    private static QueryDefinition getQuery(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Choose a valid table and query option.");
        }

        String[] parts = path.substring(1).split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Choose a valid table and query option.");
        }

        String table = TABLES.get(parts[0]);
        if (table == null) {
            throw new IllegalArgumentException(
                    "Queries are not available for that table.");
        }
        if (!QUERY_TYPES.contains(parts[1])) {
            throw new IllegalArgumentException(
                    "That query option is not supported.");
        }

        String sql = QUERIES.get(parts[0]).get(parts[1]);
        return new QueryDefinition(table, sql);
    }

    private static void writeJson(HttpServletResponse response, int status,
                                  Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), body);
    }

    private static Map<String, Map<String, String>> createQueries() {
        Map<String, Map<String, String>> queries = new LinkedHashMap<>();
        queries.put("companies", Map.of(
                "simple", """
                        SELECT * FROM COMPANY
                        WHERE COMPANY_ID IS NOT NULL
                        ORDER BY COMPANY_ID
                        """,
                "aggregate", """
                        SELECT INDUSTRY_TYPE, COUNT(*) AS COMPANY_COUNT,
                               MIN(COMPANY_ID) AS FIRST_COMPANY_ID,
                               MAX(COMPANY_ID) AS LAST_COMPANY_ID
                        FROM COMPANY
                        GROUP BY INDUSTRY_TYPE
                        ORDER BY COMPANY_COUNT DESC
                        """,
                "complex", """
                        SELECT c.COMPANY_ID, c.COMPANY_NAME, c.INDUSTRY_TYPE,
                               COUNT(j.JOB_ID) AS JOB_COUNT
                        FROM COMPANY c
                        LEFT JOIN JOB_POSTING j
                            ON j.COMPANY_ID = c.COMPANY_ID
                        WHERE c.COMPANY_ID > 0
                          AND UPPER(c.INDUSTRY_TYPE) <> 'UNKNOWN'
                        GROUP BY c.COMPANY_ID, c.COMPANY_NAME,
                                 c.INDUSTRY_TYPE
                        HAVING COUNT(j.JOB_ID) >= 1
                        ORDER BY JOB_COUNT DESC, c.COMPANY_NAME
                        """,
                "join", """
                        SELECT c.COMPANY_ID, c.COMPANY_NAME, c.INDUSTRY_TYPE,
                               j.JOB_ID, j.JOB_TITLE, j.JOB_STATUS
                        FROM COMPANY c
                        LEFT JOIN JOB_POSTING j
                            ON j.COMPANY_ID = c.COMPANY_ID
                        ORDER BY c.COMPANY_NAME, j.JOB_TITLE
                        """,
                "nested", """
                        SELECT COMPANY_ID, COMPANY_NAME, INDUSTRY_TYPE
                        FROM COMPANY
                        WHERE COMPANY_ID IN (
                            SELECT COMPANY_ID
                            FROM JOB_POSTING
                            WHERE UPPER(JOB_STATUS) = 'OPEN'
                        )
                        ORDER BY COMPANY_NAME
                        """,
                "functions", """
                        SELECT COMPANY_ID,
                               UPPER(COMPANY_NAME) AS COMPANY_NAME_UPPER,
                               LOWER(NVL(WEBSITE, 'not provided')) AS WEBSITE_LOWER,
                               LENGTH(COMPANY_NAME) AS NAME_LENGTH,
                               NVL(CONTACT_EMAIL, 'not provided') AS CONTACT_EMAIL
                        FROM COMPANY
                        ORDER BY COMPANY_ID
                        """));
        queries.put("jobs", Map.of(
                "simple", """
                        SELECT * FROM JOB_POSTING
                        WHERE JOB_ID IS NOT NULL
                        ORDER BY JOB_ID
                        """,
                "aggregate", """
                        SELECT JOB_TYPE, COUNT(*) AS JOB_COUNT,
                               SUM(ANNUAL_PACKAGE) AS TOTAL_PACKAGE_LPA,
                               ROUND(AVG(ANNUAL_PACKAGE), 2) AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE) AS MAX_PACKAGE_LPA,
                               SUM(NUMBER_OF_VACANCIES) AS TOTAL_VACANCIES
                        FROM JOB_POSTING
                        GROUP BY JOB_TYPE
                        ORDER BY JOB_COUNT DESC
                        """,
                "complex", """
                        SELECT JOB_TYPE, JOB_LOCATION, COUNT(*) AS JOB_COUNT,
                               ROUND(AVG(ANNUAL_PACKAGE), 2) AS AVG_PACKAGE_LPA
                        FROM JOB_POSTING
                        WHERE ANNUAL_PACKAGE > 0
                          AND NUMBER_OF_VACANCIES > 0
                          AND APPLICATION_DEADLINE >= TRUNC(SYSDATE)
                        GROUP BY JOB_TYPE, JOB_LOCATION
                        HAVING COUNT(*) >= 1
                        ORDER BY AVG_PACKAGE_LPA DESC
                        """,
                "join", """
                        SELECT c.COMPANY_NAME, j.JOB_ID, j.JOB_TITLE,
                               j.JOB_TYPE, j.ANNUAL_PACKAGE,
                               j.JOB_STATUS, a.APPLICATION_ID,
                               a.APPLICATION_STATUS
                        FROM JOB_POSTING j
                        JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        LEFT JOIN APPLICATION a ON a.JOB_ID = j.JOB_ID
                        ORDER BY j.JOB_ID, a.APPLICATION_ID
                        """,
                "nested", """
                        SELECT JOB_ID, JOB_TITLE, JOB_TYPE, ANNUAL_PACKAGE,
                               JOB_STATUS
                        FROM JOB_POSTING j
                        WHERE EXISTS (
                            SELECT 1
                            FROM APPLICATION a
                            WHERE a.JOB_ID = j.JOB_ID
                              AND a.APPLICATION_STATUS IS NOT NULL
                        )
                        ORDER BY JOB_TITLE
                        """,
                "functions", """
                        SELECT JOB_ID, UPPER(JOB_TITLE) AS JOB_TITLE_UPPER,
                               LENGTH(JOB_TITLE) AS TITLE_LENGTH,
                               ROUND(ANNUAL_PACKAGE, 2) AS PACKAGE_LPA,
                               NVL(JOB_DESCRIPTION, 'No description')
                                   AS JOB_DESCRIPTION,
                               TO_CHAR(APPLICATION_DEADLINE, 'YYYY-MM-DD')
                                   AS APPLICATION_DEADLINE
                        FROM JOB_POSTING
                        ORDER BY JOB_ID
                        """));
        queries.put("students", Map.of(
                "simple", """
                        SELECT * FROM STUDENT
                        WHERE STUDENT_ID IS NOT NULL
                        ORDER BY STUDENT_ID
                        """,
                "aggregate", """
                        SELECT DEPARTMENT, COUNT(*) AS STUDENT_COUNT,
                               ROUND(AVG(CGPA), 2) AS AVG_CGPA,
                               MIN(CGPA) AS MIN_CGPA,
                               MAX(CGPA) AS MAX_CGPA,
                               MIN(GRADUATION_YEAR) AS EARLIEST_GRADUATION,
                               MAX(GRADUATION_YEAR) AS LATEST_GRADUATION
                        FROM STUDENT
                        GROUP BY DEPARTMENT
                        ORDER BY STUDENT_COUNT DESC
                        """,
                "complex", """
                        SELECT DEPARTMENT, COUNT(*) AS ELIGIBLE_STUDENTS,
                               ROUND(AVG(CGPA), 2) AS AVG_CGPA
                        FROM STUDENT
                        WHERE CGPA >= 0
                          AND YEAR_OF_STUDY > 0
                          AND GRADUATION_YEAR >= EXTRACT(YEAR FROM SYSDATE)
                        GROUP BY DEPARTMENT
                        HAVING AVG(CGPA) >= 0
                        ORDER BY AVG_CGPA DESC
                        """,
                "join", """
                        SELECT s.STUDENT_ID, s.FULL_NAME, s.DEPARTMENT,
                               a.APPLICATION_ID, a.APPLICATION_STATUS,
                               j.JOB_TITLE, c.COMPANY_NAME
                        FROM STUDENT s
                        LEFT JOIN APPLICATION a ON a.STUDENT_ID = s.STUDENT_ID
                        LEFT JOIN JOB_POSTING j ON j.JOB_ID = a.JOB_ID
                        LEFT JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        ORDER BY s.STUDENT_ID, a.APPLICATION_ID
                        """,
                "nested", """
                        SELECT STUDENT_ID, FULL_NAME, DEPARTMENT, CGPA
                        FROM STUDENT s
                        WHERE s.STUDENT_ID IN (
                            SELECT a.STUDENT_ID
                            FROM APPLICATION a
                            JOIN JOB_POSTING j ON j.JOB_ID = a.JOB_ID
                            WHERE UPPER(j.JOB_STATUS) = 'OPEN'
                        )
                        ORDER BY FULL_NAME
                        """,
                "functions", """
                        SELECT STUDENT_ID, UPPER(FULL_NAME) AS NAME_UPPER,
                               LOWER(EMAIL) AS EMAIL_LOWER,
                               LENGTH(DEPARTMENT) AS DEPARTMENT_LENGTH,
                               ROUND(CGPA, 2) AS CGPA_ROUNDED,
                               NVL(PHONE_NUMBER, 'Not provided') AS PHONE_NUMBER,
                               EXTRACT(YEAR FROM SYSDATE) AS CURRENT_YEAR
                        FROM STUDENT
                        ORDER BY STUDENT_ID
                        """));
        queries.put("applications", Map.of(
                "simple", """
                        SELECT * FROM APPLICATION
                        WHERE APPLICATION_ID IS NOT NULL
                        ORDER BY APPLICATION_ID
                        """,
                "aggregate", """
                        SELECT APPLICATION_STATUS,
                               COUNT(*) AS APPLICATION_COUNT,
                               COUNT(DISTINCT STUDENT_ID) AS STUDENT_COUNT,
                               COUNT(DISTINCT JOB_ID) AS JOB_COUNT,
                               MIN(APPLICATION_DATE) AS FIRST_APPLICATION,
                               MAX(APPLICATION_DATE) AS LAST_APPLICATION
                        FROM APPLICATION
                        GROUP BY APPLICATION_STATUS
                        ORDER BY APPLICATION_COUNT DESC
                        """,
                "complex", """
                        SELECT APPLICATION_STATUS, COUNT(*) AS APPLICATION_COUNT,
                               COUNT(DISTINCT STUDENT_ID) AS STUDENT_COUNT
                        FROM APPLICATION
                        WHERE APPLICATION_DATE <= SYSDATE
                          AND STUDENT_ID > 0
                          AND JOB_ID > 0
                        GROUP BY APPLICATION_STATUS
                        HAVING COUNT(*) >= 1
                        ORDER BY APPLICATION_COUNT DESC
                        """,
                "join", """
                        SELECT a.APPLICATION_ID, a.APPLICATION_DATE,
                               a.APPLICATION_STATUS, s.FULL_NAME,
                               s.EMAIL, j.JOB_TITLE, c.COMPANY_NAME
                        FROM APPLICATION a
                        JOIN STUDENT s ON s.STUDENT_ID = a.STUDENT_ID
                        JOIN JOB_POSTING j ON j.JOB_ID = a.JOB_ID
                        JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        ORDER BY a.APPLICATION_DATE DESC, a.APPLICATION_ID
                        """,
                "nested", """
                        SELECT a.APPLICATION_ID, a.STUDENT_ID, a.JOB_ID,
                               a.APPLICATION_DATE, a.APPLICATION_STATUS
                        FROM APPLICATION a
                        WHERE EXISTS (
                            SELECT 1
                            FROM INTERVIEW i
                            WHERE i.APPLICATION_ID = a.APPLICATION_ID
                        )
                        ORDER BY a.APPLICATION_DATE DESC
                        """,
                "functions", """
                        SELECT APPLICATION_ID,
                               UPPER(APPLICATION_STATUS) AS STATUS_UPPER,
                               NVL(RESUME_SUBMITTED, 'Unknown') AS RESUME_STATUS,
                               TO_CHAR(APPLICATION_DATE, 'YYYY-MM-DD')
                                   AS APPLICATION_DATE,
                               TRUNC(APPLICATION_DATE) AS APPLICATION_DAY,
                               LENGTH(NVL(REMARKS, '')) AS REMARKS_LENGTH
                        FROM APPLICATION
                        ORDER BY APPLICATION_ID
                        """));
        queries.put("interviews", Map.of(
                "simple", """
                        SELECT * FROM INTERVIEW
                        WHERE INTERVIEW_ID IS NOT NULL
                        ORDER BY INTERVIEW_ID
                        """,
                "aggregate", """
                        SELECT INTERVIEW_STATUS, COUNT(*) AS INTERVIEW_COUNT,
                               ROUND(AVG(INTERVIEW_ROUND), 2) AS AVG_ROUND,
                               MIN(INTERVIEW_ROUND) AS FIRST_ROUND,
                               MAX(INTERVIEW_ROUND) AS LAST_ROUND,
                               MIN(INTERVIEW_DATE) AS FIRST_INTERVIEW,
                               MAX(INTERVIEW_DATE) AS LAST_INTERVIEW
                        FROM INTERVIEW
                        GROUP BY INTERVIEW_STATUS
                        ORDER BY INTERVIEW_COUNT DESC
                        """,
                "complex", """
                        SELECT INTERVIEW_STATUS, INTERVIEW_MODE,
                               COUNT(*) AS INTERVIEW_COUNT,
                               ROUND(AVG(INTERVIEW_ROUND), 2) AS AVG_ROUND
                        FROM INTERVIEW
                        WHERE INTERVIEW_DATE >= TRUNC(SYSDATE)
                          AND INTERVIEW_ROUND > 0
                        GROUP BY INTERVIEW_STATUS, INTERVIEW_MODE
                        HAVING COUNT(*) >= 1
                        ORDER BY INTERVIEW_COUNT DESC
                        """,
                "join", """
                        SELECT i.INTERVIEW_ID, i.INTERVIEW_ROUND,
                               i.INTERVIEW_DATE, i.INTERVIEW_MODE,
                               i.INTERVIEW_STATUS, s.FULL_NAME,
                               j.JOB_TITLE, c.COMPANY_NAME
                        FROM INTERVIEW i
                        JOIN APPLICATION a
                            ON a.APPLICATION_ID = i.APPLICATION_ID
                        JOIN STUDENT s ON s.STUDENT_ID = a.STUDENT_ID
                        JOIN JOB_POSTING j ON j.JOB_ID = a.JOB_ID
                        JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        ORDER BY i.INTERVIEW_DATE, i.INTERVIEW_ROUND
                        """,
                "nested", """
                        SELECT INTERVIEW_ID, APPLICATION_ID, INTERVIEW_ROUND,
                               INTERVIEW_DATE, INTERVIEW_STATUS
                        FROM INTERVIEW
                        WHERE APPLICATION_ID IN (
                            SELECT APPLICATION_ID
                            FROM OFFER
                            WHERE APPLICATION_ID IS NOT NULL
                        )
                        ORDER BY INTERVIEW_DATE
                        """,
                "functions", """
                        SELECT INTERVIEW_ID,
                               UPPER(INTERVIEW_MODE) AS MODE_UPPER,
                               LOWER(NVL(INTERVIEWER_NAME, 'unassigned'))
                                   AS INTERVIEWER_LOWER,
                               LENGTH(NVL(LOCATION_MEETING_LINK, ''))
                                   AS LOCATION_LENGTH,
                               TO_CHAR(INTERVIEW_DATE, 'YYYY-MM-DD')
                                   AS INTERVIEW_DATE,
                               EXTRACT(YEAR FROM INTERVIEW_DATE) AS INTERVIEW_YEAR
                        FROM INTERVIEW
                        ORDER BY INTERVIEW_ID
                        """));
        queries.put("offers", Map.of(
                "simple", """
                        SELECT * FROM OFFER
                        WHERE OFFER_ID IS NOT NULL
                        ORDER BY OFFER_ID
                        """,
                "aggregate", """
                        SELECT OFFER_STATUS, COUNT(*) AS OFFER_COUNT,
                               SUM(ANNUAL_PACKAGE_LPA) AS TOTAL_PACKAGE_LPA,
                               ROUND(AVG(ANNUAL_PACKAGE_LPA), 2)
                                   AS AVG_PACKAGE_LPA,
                               MIN(ANNUAL_PACKAGE_LPA) AS MIN_PACKAGE_LPA,
                               MAX(ANNUAL_PACKAGE_LPA) AS MAX_PACKAGE_LPA
                        FROM OFFER
                        GROUP BY OFFER_STATUS
                        ORDER BY OFFER_COUNT DESC
                        """,
                "complex", """
                        SELECT OFFER_STATUS, COUNT(*) AS OFFER_COUNT,
                               ROUND(AVG(ANNUAL_PACKAGE_LPA), 2)
                                   AS AVG_PACKAGE_LPA
                        FROM OFFER
                        WHERE ANNUAL_PACKAGE_LPA > 0
                          AND (OFFER_DATE IS NULL OR OFFER_DATE <= SYSDATE)
                        GROUP BY OFFER_STATUS
                        HAVING COUNT(APPLICATION_ID) > 0
                        ORDER BY AVG_PACKAGE_LPA DESC
                        """,
                "join", """
                        SELECT o.OFFER_ID, o.OFFER_STATUS,
                               o.ANNUAL_PACKAGE_LPA, s.FULL_NAME,
                               j.JOB_TITLE, c.COMPANY_NAME,
                               a.APPLICATION_STATUS
                        FROM OFFER o
                        LEFT JOIN APPLICATION a
                            ON a.APPLICATION_ID = o.APPLICATION_ID
                        LEFT JOIN STUDENT s ON s.STUDENT_ID = a.STUDENT_ID
                        LEFT JOIN JOB_POSTING j ON j.JOB_ID = a.JOB_ID
                        LEFT JOIN COMPANY c ON c.COMPANY_ID = j.COMPANY_ID
                        ORDER BY o.OFFER_ID
                        """,
                "nested", """
                        SELECT OFFER_ID, APPLICATION_ID, COMPANY_NAME,
                               JOB_TITLE, OFFER_STATUS, ANNUAL_PACKAGE_LPA
                        FROM OFFER
                        WHERE APPLICATION_ID IN (
                            SELECT APPLICATION_ID
                            FROM APPLICATION
                            WHERE APPLICATION_DATE <= SYSDATE
                        )
                        ORDER BY OFFER_ID
                        """,
                "functions", """
                        SELECT OFFER_ID,
                               UPPER(NVL(OFFER_STATUS, 'pending')) AS STATUS_UPPER,
                               LOWER(NVL(OFFER_TYPE, 'unspecified')) AS TYPE_LOWER,
                               ROUND(NVL(ANNUAL_PACKAGE_LPA, 0), 2)
                                   AS PACKAGE_LPA,
                               NVL(TO_CHAR(OFFER_DATE, 'YYYY-MM-DD'), 'No date')
                                   AS OFFER_DATE,
                               TRUNC(SYSDATE) AS TODAY
                        FROM OFFER
                        ORDER BY OFFER_ID
                        """));
        return Map.copyOf(queries);
    }

    private record QueryDefinition(String table, String sql) { }
}
