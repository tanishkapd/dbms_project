package com.placement;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = {
        "/api/companies/*",
        "/api/jobs/*",
        "/api/students/*",
        "/api/applications/*",
        "/api/interviews/*",
        "/api/offers/*"
})
public class PlacementApiServlet extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INTERVIEW_ROUNDS = List.of(
            "Online Assessment", "Technical Round 1", "Technical Round 2",
            "HR Round", "Final Round");
    private static final Map<String, Entity> ENTITIES = createEntities();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        try {
            Entity entity = getEntity(request);
            String pathId = getPathId(request);
            try (Connection connection = DBConnection.getConnection()) {
                EntitySchema schema = loadSchema(connection, entity);
                if (pathId == null) {
                    writeJson(response, HttpServletResponse.SC_OK,
                            list(connection, schema));
                    return;
                }

                Object id = parseId(pathId);
                Map<String, Object> record = get(connection, schema, id);
                if (record == null) {
                    writeError(response, HttpServletResponse.SC_NOT_FOUND,
                            entity.label() + " not found.");
                    return;
                }
                writeJson(response, HttpServletResponse.SC_OK, record);
            }
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage());
        } catch (SQLException e) {
            reportDatabaseError("read", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read records.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        try {
            if (getPathId(request) != null) {
                writeError(response,
                        HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        "POST is supported only at the collection URL.");
                return;
            }
            Entity entity = getEntity(request);
            Map<String, Object> input = readInput(request, entity);
            validateInput(entity, input, true);

            try (Connection connection = DBConnection.getConnection()) {
                EntitySchema schema = loadSchema(connection, entity);
                insert(connection, schema, input);
                Map<String, Object> created = get(
                        connection, schema, parseId(input.get("id").toString()));
                response.setHeader("Location",
                        request.getRequestURI() + "/" + input.get("id"));
                writeJson(response, HttpServletResponse.SC_CREATED, created);
            }
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage());
        } catch (SQLException e) {
            reportDatabaseError("create", e);
            writeSqlError(response, e, "Unable to create record.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        try {
            String pathId = getPathId(request);
            if (pathId == null) {
                writeError(response,
                        HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        "PUT requires a record ID in the URL.");
                return;
            }
            Entity entity = getEntity(request);
            Object id = parseId(pathId);
            Map<String, Object> input = readInput(request, entity);
            validateInput(entity, input, false);
            if (input.containsKey("id")
                    && ((BigDecimal) id).compareTo((BigDecimal)
                            parseId(input.get("id").toString())) != 0) {
                throw new IllegalArgumentException(
                        "ID in the URL and request body must match.");
            }

            try (Connection connection = DBConnection.getConnection()) {
                EntitySchema schema = loadSchema(connection, entity);
                if (!update(connection, schema, id, input)) {
                    writeError(response, HttpServletResponse.SC_NOT_FOUND,
                            entity.label() + " not found.");
                    return;
                }
                writeJson(response, HttpServletResponse.SC_OK,
                        get(connection, schema, id));
            }
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage());
        } catch (SQLException e) {
            reportDatabaseError("update", e);
            writeSqlError(response, e, "Unable to update record.");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        try {
            String pathId = getPathId(request);
            if (pathId == null) {
                writeError(response,
                        HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        "DELETE requires a record ID in the URL.");
                return;
            }
            Entity entity = getEntity(request);
            Object id = parseId(pathId);
            try (Connection connection = DBConnection.getConnection()) {
                EntitySchema schema = loadSchema(connection, entity);
                String sql = "DELETE FROM " + quote(entity.table())
                        + " WHERE " + quote(schema.column("id").name()) + " = ?";
                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {
                    setValue(statement, 1, id, schema.column("id"),
                            "id");
                    if (statement.executeUpdate() == 0) {
                        writeError(response, HttpServletResponse.SC_NOT_FOUND,
                                entity.label() + " not found.");
                        return;
                    }
                }
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage());
        } catch (SQLException e) {
            reportDatabaseError("delete", e);
            writeSqlError(response, e, "Unable to delete record.");
        }
    }

    private static List<Map<String, Object>> list(
            Connection connection, EntitySchema schema) throws SQLException {
        String sql = selectSql(schema) + " ORDER BY "
                + quote(schema.column("id").name());
        List<Map<String, Object>> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                records.add(readRecord(results, schema));
            }
        }
        return records;
    }

    private static Map<String, Object> get(
            Connection connection, EntitySchema schema, Object id)
            throws SQLException {
        String sql = selectSql(schema) + " WHERE "
                + quote(schema.column("id").name()) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setValue(statement, 1, id, schema.column("id"), "id");
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? readRecord(results, schema)
                        : null;
            }
        }
    }

    private static String selectSql(EntitySchema schema) {
        List<String> selections = new ArrayList<>();
        for (Field field : schema.entity().fields()) {
            Column column = schema.columns().get(field.name());
            String expression = column == null ? "NULL" : quote(column.name());
            selections.add(expression + " AS " + quote(field.name()));
        }
        return "SELECT " + String.join(", ", selections)
                + " FROM " + quote(schema.entity().table());
    }

    private static Map<String, Object> readRecord(
            ResultSet results, EntitySchema schema) throws SQLException {
        Map<String, Object> record = new LinkedHashMap<>();
        for (Field field : schema.entity().fields()) {
            record.put(field.name(),
                    normalizeValue(results.getObject(field.name()), field,
                            schema.columns().get(field.name())));
        }
        return record;
    }

    private static Object normalizeValue(Object value, Field field,
                                         Column column) {
        if (value == null) {
            return null;
        }
        if (field.name().equals("round") && column != null
                && isNumeric(column.sqlType())) {
            try {
                int round = new BigDecimal(value.toString()).intValueExact();
                return round >= 1 && round <= INTERVIEW_ROUNDS.size()
                        ? INTERVIEW_ROUNDS.get(round - 1)
                        : value.toString();
            } catch (NumberFormatException | ArithmeticException e) {
                return value.toString();
            }
        }
        if (field.dateField()) {
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate().toString();
            }
            if (value instanceof Timestamp timestamp) {
                return timestamp.toLocalDateTime().toLocalDate().toString();
            }
            String text = value.toString();
            return text.length() >= 10 ? text.substring(0, 10) : text;
        }
        if (field.timeField()) {
            if (value instanceof java.sql.Time time) {
                return time.toLocalTime().toString();
            }
            if (value instanceof Timestamp timestamp) {
                return timestamp.toLocalDateTime().toLocalTime()
                        .toString().substring(0, 5);
            }
            String text = value.toString();
            if (text.length() >= 16 && text.charAt(10) == ' ') {
                return text.substring(11, 16);
            }
            return text.length() >= 5 ? text.substring(0, 5) : text;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        return value;
    }

    private void insert(Connection connection, EntitySchema schema,
                        Map<String, Object> input) throws SQLException {
        requireSupportedFields(schema, input);
        List<Field> fields = schema.entity().fields().stream()
                .filter(field -> input.containsKey(field.name())
                        && schema.columns().containsKey(field.name()))
                .toList();
        String columns = fields.stream()
                .map(field -> quote(schema.column(field.name()).name()))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(fields.size(), "?"));
        String sql = "INSERT INTO " + quote(schema.entity().table())
                + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                setValue(statement, i + 1, input.get(field.name()),
                        schema.column(field.name()), field.name());
            }
            statement.executeUpdate();
        }
    }

    private boolean update(Connection connection, EntitySchema schema,
                           Object id, Map<String, Object> input)
            throws SQLException {
        requireSupportedFields(schema, input);
        List<Field> fields = schema.entity().fields().stream()
                .filter(field -> !field.name().equals("id")
                        && input.containsKey(field.name())
                        && schema.columns().containsKey(field.name()))
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(
                    "Provide at least one field to update.");
        }
        String assignments = fields.stream()
                .map(field -> quote(schema.column(field.name()).name()) + " = ?")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String sql = "UPDATE " + quote(schema.entity().table())
                + " SET " + assignments + " WHERE "
                + quote(schema.column("id").name()) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                setValue(statement, i + 1, input.get(field.name()),
                        schema.column(field.name()), field.name());
            }
            setValue(statement, fields.size() + 1, id,
                    schema.column("id"), "id");
            return statement.executeUpdate() > 0;
        }
    }

    private void requireSupportedFields(
            EntitySchema schema, Map<String, Object> input) {
        for (String name : input.keySet()) {
            if (!name.equals("id")
                    && !schema.columns().containsKey(name)
                    && hasValue(input.get(name))) {
                getServletContext().log(
                        "Rejected unsupported " + schema.entity().table()
                                + " field " + name + ". Existing columns: "
                                + String.join(", ",
                                        schema.availableColumns()));
                throw new IllegalArgumentException(
                        "The existing " + schema.entity().table()
                                + " table does not support field: " + name);
            }
        }
    }

    private static boolean hasValue(Object value) {
        return value != null
                && (!(value instanceof String text) || !text.isBlank());
    }

    private static EntitySchema loadSchema(Connection connection,
                                           Entity entity)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Column> actualColumns = new LinkedHashMap<>();
        try (ResultSet results = metadata.getColumns(
                connection.getCatalog(), connection.getSchema(),
                entity.table(), null)) {
            while (results.next()) {
                Column column = new Column(
                        results.getString("COLUMN_NAME"),
                        results.getInt("DATA_TYPE"),
                        results.getInt("COLUMN_SIZE"));
                actualColumns.put(normalize(column.name()), column);
            }
        }
        if (actualColumns.isEmpty()) {
            throw new SQLException(
                    entity.table() + " table was not found in the current Oracle schema.");
        }

        Map<String, Column> resolved = new LinkedHashMap<>();
        for (Field field : entity.fields()) {
            for (String alias : field.aliases()) {
                Column column = actualColumns.get(normalize(alias));
                if (column != null) {
                    resolved.put(field.name(), column);
                    break;
                }
            }
        }
        for (String name : entity.requiredFields()) {
            if (!resolved.containsKey(name)) {
                throw new SQLException(entity.table()
                        + " table is missing a required column for " + name
                        + ". Available columns: "
                        + actualColumns.values().stream()
                                .map(Column::name)
                                .reduce((left, right) -> left + ", " + right)
                                .orElse("(none)"));
            }
        }
        return new EntitySchema(entity, resolved,
                actualColumns.values().stream().map(Column::name).toList());
    }

    private static Map<String, Object> readInput(
            HttpServletRequest request, Entity entity) throws IOException {
        Map<String, Object> input;
        try {
            input = JSON.readValue(request.getInputStream(),
                    new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Request body must be a valid JSON object.");
        }
        if (input == null) {
            throw new IllegalArgumentException(
                    "Request body must be a JSON object.");
        }
        List<String> allowed = entity.fields().stream()
                .map(Field::name)
                .toList();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Unsupported " + entity.label() + " field: "
                                + entry.getKey());
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String)
                    && !(value instanceof Number)) {
                throw new IllegalArgumentException(
                        "Field values must be strings or numbers.");
            }
            if (value instanceof Number number
                    && !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException(
                        "Numeric field values must be finite.");
            }
        }
        return input;
    }

    private static void validateInput(Entity entity,
                                     Map<String, Object> input,
                                     boolean requireId) {
        if (requireId && !input.containsKey("id")) {
            throw new IllegalArgumentException(entity.label() + " ID is required.");
        }
        if (requireId) {
            parseId(String.valueOf(input.get("id")));
        }
        for (String name : entity.requiredFields()) {
            Object value = input.get(name);
            if (!hasValue(value)) {
                throw new IllegalArgumentException(
                        entity.label() + " " + name + " is required.");
            }
        }

        for (Field field : entity.fields()) {
            Object value = input.get(field.name());
            if (!hasValue(value)) {
                continue;
            }
            String text = value.toString();
            if (field.dateField()) {
                try {
                    LocalDate.parse(text);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            field.name() + " must use YYYY-MM-DD format.");
                }
            } else if (field.timeField()) {
                try {
                    LocalTime.parse(text);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            field.name() + " must use HH:MM format.");
                }
            }
            if ((field.name().equals("email"))
                    && !text.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new IllegalArgumentException(
                        "Email must be a valid email address.");
            }
        }

        if (entity.name().equals("students")
                && hasValue(input.get("cgpa"))) {
            BigDecimal cgpa = decimal(input.get("cgpa").toString(), "CGPA");
            if (cgpa.signum() < 0 || cgpa.compareTo(BigDecimal.TEN) > 0) {
                throw new IllegalArgumentException(
                        "CGPA must be between 0 and 10.");
            }
        }
        if (entity.name().equals("jobs")) {
            if (hasValue(input.get("packageLpa"))
                    && decimal(input.get("packageLpa").toString(),
                            "Package").signum() < 0) {
                throw new IllegalArgumentException(
                        "Package must not be negative.");
            }
            if (hasValue(input.get("vacancies"))
                    && decimal(input.get("vacancies").toString(),
                            "Vacancies").signum() <= 0) {
                throw new IllegalArgumentException(
                        "Vacancies must be a positive number.");
            }
        }
        if (entity.name().equals("students")) {
            validateIntegerRange(input, "year", 1, 4);
            validateIntegerRange(input, "graduationYear", 2000, 2100);
        }
        if (entity.name().equals("offers")
                && hasValue(input.get("packageLpa"))
                && decimal(input.get("packageLpa").toString(),
                        "Package").signum() < 0) {
            throw new IllegalArgumentException(
                    "Package must not be negative.");
        }
        if (entity.name().equals("offers")
                && hasValue(input.get("joiningDate"))
                && input.containsKey("offerDate")
                && LocalDate.parse(input.get("joiningDate").toString())
                        .isBefore(LocalDate.parse(
                                input.get("offerDate").toString()))) {
            throw new IllegalArgumentException(
                    "Joining date cannot be earlier than the offer date.");
        }
        if (entity.name().equals("companies")
                && hasValue(input.get("website"))) {
            String website = input.get("website").toString();
            if (!(website.startsWith("https://")
                    || website.startsWith("http://"))) {
                throw new IllegalArgumentException(
                        "Company website must start with http:// or https://.");
            }
        }
    }

    private static void validateIntegerRange(Map<String, Object> input,
                                             String field, int minimum,
                                             int maximum) {
        if (!hasValue(input.get(field))) {
            return;
        }
        BigDecimal value = decimal(input.get(field).toString(), field);
        try {
            int integer = value.intValueExact();
            if (integer < minimum || integer > maximum) {
                throw new IllegalArgumentException(
                        field + " must be between " + minimum
                                + " and " + maximum + ".");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    field + " must be a whole number.");
        }
    }

    private static void setValue(PreparedStatement statement, int index,
                                 Object value, Column column, String field)
            throws SQLException {
        if (!hasValue(value)) {
            statement.setNull(index, column.sqlType());
            return;
        }
        String text = value.toString();
        if (isText(column.sqlType()) && column.size() > 0
                && text.length() > column.size()) {
            throw new IllegalArgumentException(
                    field + " exceeds the database column length.");
        }
        if (isNumeric(column.sqlType())) {
            if (field.equals("round")) {
                int roundIndex = INTERVIEW_ROUNDS.indexOf(text);
                if (roundIndex >= 0) {
                    statement.setBigDecimal(index,
                            BigDecimal.valueOf(roundIndex + 1));
                    return;
                }
            }
            statement.setBigDecimal(index, decimal(text, field));
        } else if (column.sqlType() == Types.DATE) {
            if (field.equals("time")) {
                LocalDateTime time = LocalDate.of(1970, 1, 1)
                        .atTime(LocalTime.parse(text));
                statement.setTimestamp(index, Timestamp.valueOf(time));
            } else {
                statement.setDate(index,
                        java.sql.Date.valueOf(LocalDate.parse(text)));
            }
        } else if (column.sqlType() == Types.TIME
                || column.sqlType() == Types.TIME_WITH_TIMEZONE) {
            statement.setTime(index, java.sql.Time.valueOf(
                    LocalTime.parse(text)));
        } else if (column.sqlType() == Types.TIMESTAMP
                || column.sqlType() == Types.TIMESTAMP_WITH_TIMEZONE) {
            if (field.equals("time")) {
                statement.setTimestamp(index, Timestamp.valueOf(
                        LocalDate.of(1970, 1, 1)
                                .atTime(LocalTime.parse(text))));
            } else if (field.equals("date")
                    || field.equals("deadline")
                    || field.equals("offerDate")
                    || field.equals("joiningDate")) {
                statement.setTimestamp(index, Timestamp.valueOf(
                        LocalDate.parse(text).atStartOfDay()));
            } else {
                statement.setString(index, text);
            }
        } else {
            statement.setString(index, text);
        }
    }

    private static BigDecimal decimal(String text, String field) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    field + " must be a valid number.");
        }
    }

    private static boolean isNumeric(int sqlType) {
        return sqlType == Types.NUMERIC || sqlType == Types.DECIMAL
                || sqlType == Types.TINYINT || sqlType == Types.SMALLINT
                || sqlType == Types.INTEGER || sqlType == Types.BIGINT
                || sqlType == Types.FLOAT || sqlType == Types.REAL
                || sqlType == Types.DOUBLE;
    }

    private static boolean isText(int sqlType) {
        return sqlType == Types.CHAR || sqlType == Types.VARCHAR
                || sqlType == Types.LONGVARCHAR
                || sqlType == Types.NCHAR || sqlType == Types.NVARCHAR
                || sqlType == Types.LONGNVARCHAR || sqlType == Types.CLOB
                || sqlType == Types.NCLOB;
    }

    private static Object parseId(String text) {
        BigDecimal id = decimal(text, "ID");
        try {
            id.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "ID must be a positive whole number.");
        }
        if (id.signum() <= 0) {
            throw new IllegalArgumentException(
                    "ID must be a positive whole number.");
        }
        return id;
    }

    private static Entity getEntity(HttpServletRequest request) {
        String path = request.getServletPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        Entity entity = ENTITIES.get(name);
        if (entity == null) {
            throw new IllegalArgumentException("Unknown API resource.");
        }
        return entity;
    }

    private static String getPathId(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.equals("/")) {
            return null;
        }
        if (!path.startsWith("/") || path.length() == 1
                || path.indexOf('/', 1) >= 0) {
            throw new IllegalArgumentException(
                    "Resource URL must use a collection or collection/{id} path.");
        }
        return path.substring(1);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void writeSqlError(HttpServletResponse response, SQLException e,
                               String defaultMessage) throws IOException {
        int status = switch (e.getErrorCode()) {
            case 1, 2291, 2292 -> HttpServletResponse.SC_CONFLICT;
            case 1400, 12899 -> HttpServletResponse.SC_BAD_REQUEST;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
        String message = status == HttpServletResponse.SC_CONFLICT
                ? "Record conflicts with an existing record or reference."
                : status == HttpServletResponse.SC_BAD_REQUEST
                        ? "Submitted data violates a database constraint."
                        : defaultMessage;
        writeError(response, status, message);
    }

    private void reportDatabaseError(String operation, SQLException error) {
        getServletContext().log(
                "Placement API database " + operation + " failed.", error);
    }

    private static void writeError(HttpServletResponse response, int status,
                                   String message) throws IOException {
        writeJson(response, status, Map.of("error", message));
    }

    private static void writeJson(HttpServletResponse response, int status,
                                  Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), body);
    }

    private static Map<String, Entity> createEntities() {
        Map<String, Entity> entities = new LinkedHashMap<>();
        entities.put("companies", entity("companies", "COMPANY",
                List.of(
                        field("id", "COMPANY_ID", "ID"),
                        field("name", "COMPANY_NAME", "NAME"),
                        field("industry_type", "INDUSTRY_TYPE", "INDUSTRY"),
                        field("website", "WEBSITE", "COMPANY_WEBSITE"),
                        field("location", "LOCATION"),
                        field("contact", "CONTACT_PERSON", "CONTACT"),
                        field("email", "EMAIL", "CONTACT_EMAIL"),
                        field("phone_no", "PHONE_NO", "PHONE_NUMBER",
                                "CONTACT_PHONE")),
                "id", "name", "industry_type", "location"));
        entities.put("jobs", entity("jobs", "JOB_POSTING",
                List.of(
                        field("id", "JOB_ID", "ID"),
                        field("companyId", "COMPANY_ID"),
                        field("title", "JOB_TITLE", "TITLE"),
                        field("type", "JOB_TYPE", "TYPE"),
                        field("location", "JOB_LOCATION", "LOCATION"),
                        field("packageLpa", "PACKAGE_LPA", "ANNUAL_PACKAGE",
                                "ANNUAL_PACKAGE_LPA", "PACKAGE"),
                        field("eligibility", "ELIGIBILITY"),
                        field("vacancies", "VACANCIES", "NUMBER_OF_VACANCIES"),
                        dateField("deadline", "APPLICATION_DEADLINE",
                                "DEADLINE"),
                        field("status", "JOB_STATUS", "STATUS"),
                        field("description", "JOB_DESCRIPTION",
                                "DESCRIPTION")),
                "id", "companyId", "title", "type", "location",
                "packageLpa", "eligibility", "vacancies", "deadline",
                "status"));
        entities.put("students", entity("students", "STUDENT",
                List.of(
                        field("id", "STUDENT_ID", "ID"),
                        field("name", "STUDENT_NAME", "FULL_NAME", "NAME"),
                        field("email", "EMAIL"),
                        field("phone", "PHONE", "PHONE_NO", "PHONE_NUMBER"),
                        field("department", "DEPARTMENT"),
                        field("year", "YEAR_OF_STUDY", "YEAR"),
                        field("cgpa", "CGPA"),
                        field("graduationYear", "GRADUATION_YEAR"),
                        field("status", "PLACEMENT_STATUS", "STATUS")),
                "id", "name", "email", "department", "year", "cgpa",
                "graduationYear", "status"));
        entities.put("applications", entity("applications", "APPLICATION",
                List.of(
                        field("id", "APPLICATION_ID", "ID"),
                        field("studentId", "STUDENT_ID"),
                        field("jobId", "JOB_ID"),
                        dateField("date", "APPLICATION_DATE", "DATE"),
                        field("status", "APPLICATION_STATUS", "STATUS"),
                        field("resume", "RESUME_STATUS", "RESUME_SUBMITTED"),
                        field("remarks", "REMARKS")),
                "id", "studentId", "jobId", "date", "status", "resume"));
        entities.put("interviews", entity("interviews", "INTERVIEW",
                List.of(
                        field("id", "INTERVIEW_ID", "ID"),
                        field("applicationId", "APPLICATION_ID"),
                        field("round", "INTERVIEW_ROUND", "ROUND"),
                        dateField("date", "INTERVIEW_DATE", "DATE"),
                        timeField("time", "INTERVIEW_TIME", "TIME"),
                        field("mode", "INTERVIEW_MODE", "MODE"),
                        field("interviewer", "INTERVIEWER",
                                "INTERVIEWER_NAME"),
                        field("location", "LOCATION", "MEETING_LINK",
                                "LOCATION_MEETING_LINK"),
                        field("status", "INTERVIEW_STATUS", "STATUS"),
                        field("result", "INTERVIEW_RESULT", "RESULT"),
                        field("remarks", "REMARKS")),
                "id", "applicationId", "round", "date", "time", "mode",
                "status", "result"));
        entities.put("offers", entity("offers", "OFFER",
                List.of(
                        field("id", "OFFER_ID", "ID"),
                        field("applicationId", "APPLICATION_ID"),
                        field("companyName", "COMPANY_NAME"),
                        field("jobTitle", "JOB_TITLE"),
                        field("packageLpa", "PACKAGE_LPA", "ANNUAL_PACKAGE",
                                "ANNUAL_PACKAGE_LPA", "PACKAGE"),
                        dateField("offerDate", "OFFER_DATE"),
                        dateField("joiningDate", "JOINING_DATE",
                                "EXPECTED_JOINING_DATE"),
                        field("status", "OFFER_STATUS", "STATUS"),
                        field("type", "OFFER_TYPE", "TYPE"),
                        field("remarks", "REMARKS")),
                "id", "applicationId", "companyName", "jobTitle",
                "packageLpa", "offerDate", "status", "type"));
        return Map.copyOf(entities);
    }

    private static Entity entity(String name, String table,
                                 List<Field> fields,
                                 String... requiredFields) {
        return new Entity(name, table, fields, List.of(requiredFields));
    }

    private static Field field(String name, String... aliases) {
        return new Field(name, List.of(aliases), false, false);
    }

    private static Field dateField(String name, String... aliases) {
        return new Field(name, List.of(aliases), true, false);
    }

    private static Field timeField(String name, String... aliases) {
        return new Field(name, List.of(aliases), false, true);
    }

    private static String normalize(String value) {
        return value.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private record Field(String name, List<String> aliases,
                         boolean dateField, boolean timeField) { }

    private record Entity(String name, String table, List<Field> fields,
                         List<String> requiredFields) {
        private String label() {
            return switch (name) {
                case "companies" -> "Company";
                case "jobs" -> "Job posting";
                case "students" -> "Student";
                case "applications" -> "Application";
                case "interviews" -> "Interview";
                case "offers" -> "Offer";
                default -> name;
            };
        }
    }

    private record Column(String name, int sqlType, int size) { }

    private record EntitySchema(Entity entity, Map<String, Column> columns,
                                List<String> availableColumns) {
        private Column column(String field) {
            Column column = columns.get(field);
            if (column == null) {
                throw new IllegalArgumentException(
                        "The existing " + entity.table()
                                + " table does not support field: " + field);
            }
            return column;
        }
    }
}
