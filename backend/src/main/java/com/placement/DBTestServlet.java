
package com.placement;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/api/db-test")
public class DBTestServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");

        try (Connection con = DBConnection.getConnection()) {
            response.getWriter().write(
                    "Success! Java is connected to Oracle XE.");
        } 
catch (SQLException e) {
    response.setStatus(
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

    response.setContentType("text/plain");
    response.setCharacterEncoding("UTF-8");

    response.getWriter().write(
            "Database error: " + e.getMessage());

    e.printStackTrace();
}

    }
}