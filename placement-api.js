window.placementApi = Object.freeze({
    async request(resource, options = {}, id = null) {
        const suffix = id === null
            ? ""
            : "/" + encodeURIComponent(id);
        let response;
        try {
            response = await fetch(
                new URL(`api/${resource}${suffix}`, window.location.href),
                {
                    ...options,
                    headers: {
                        "Content-Type": "application/json",
                        ...options.headers
                    }
                }
            );
        } catch {
            throw new Error(
                "Could not reach the Placement API. Open this page through "
                + "the running Tomcat server and check that it is deployed."
            );
        }
        const responseText = await response.text();
        let result = null;

        if (responseText) {
            try {
                result = JSON.parse(responseText);
            } catch {
                throw new Error(
                    "The server returned an invalid response. Check the backend logs."
                );
            }
        }

        if (!response.ok) {
            throw new Error(
                result?.error || `Request failed (${response.status}).`
            );
        }

        return result;
    },

    list(resource) {
        return this.request(resource);
    },

    create(resource, record) {
        return this.request(resource, {
            method: "POST",
            body: JSON.stringify(record)
        });
    },

    update(resource, id, record) {
        return this.request(resource, {
            method: "PUT",
            body: JSON.stringify(record)
        }, id);
    },

    remove(resource, id) {
        return this.request(resource, {
            method: "DELETE"
        }, id);
    },

    executeQuery(table, queryId) {
        return this.request(
            `queries/${encodeURIComponent(table)}/${encodeURIComponent(queryId)}`,
            { method: "POST" }
        );
    },

    executeAggregateReport(table, reportId, filter = null) {
        return this.request(
            `reports/${encodeURIComponent(table)}/${encodeURIComponent(reportId)}`,
            {
                method: "POST",
                body: JSON.stringify({ filter })
            }
        );
    },

    async initializeSqlQueries() {
        const sections = document.querySelectorAll("[data-sql-query-table]");
        for (const section of sections) {
            const table = section.dataset.sqlQueryTable;
            const buttons = section.querySelectorAll("[data-query-id]");
            const status = section.querySelector(".sql-query-status");
            const output = section.querySelector(".sql-query-output");
            const header = section.querySelector("thead");
            const body = section.querySelector("tbody");
            const emptyMessage = section.querySelector(".sql-query-empty");

            for (const button of buttons) {
                button.addEventListener("click", async () => {
                    buttons.forEach((queryButton) => {
                        queryButton.disabled = true;
                    });
                    section.setAttribute("aria-busy", "true");
                    output.hidden = true;
                    status.textContent = `Running ${button.textContent.trim()}...`;
                    status.classList.remove("sql-query-error");
                    header.replaceChildren();
                    body.replaceChildren();
                    emptyMessage.hidden = true;

                    try {
                        const result = await this.executeQuery(
                            table,
                            button.dataset.queryId
                        );
                        const headerRow = document.createElement("tr");
                        for (const column of result.columns) {
                            const cell = document.createElement("th");
                            cell.scope = "col";
                            cell.textContent = column;
                            headerRow.append(cell);
                        }
                        header.append(headerRow);

                        for (const row of result.rows) {
                            const resultRow = document.createElement("tr");
                            for (const value of row) {
                                const cell = document.createElement("td");
                                cell.textContent = value ?? "—";
                                resultRow.append(cell);
                            }
                            body.append(resultRow);
                        }

                        output.hidden = false;
                        emptyMessage.hidden = result.rows.length > 0;
                        status.textContent = result.message
                            || `${result.rows.length} record(s) returned.`;
                    } catch (error) {
                        status.textContent = error.message;
                        status.classList.add("sql-query-error");
                    } finally {
                        section.removeAttribute("aria-busy");
                        buttons.forEach((queryButton) => {
                            queryButton.disabled = false;
                        });
                    }
                });
            }
        }
    }

    async initializeAggregateReports() {
        const sections = document.querySelectorAll(
            "[data-aggregate-table]"
        );
        for (const section of sections) {
            const table = section.dataset.aggregateTable;
            const buttons = section.querySelectorAll("[data-report-id]");
            const filterInput = section.querySelector(
                "[data-report-filter]"
            );
            const filterLabel = section.querySelector(
                "[data-report-filter-label]"
            );
            const status = section.querySelector(
                ".aggregate-report-status"
            );
            const output = section.querySelector(
                ".aggregate-report-output"
            );
            const header = section.querySelector("thead");
            const body = section.querySelector("tbody");
            const emptyMessage = section.querySelector(
                ".aggregate-report-empty"
            );
            let selectedReport = null;
            let appliedFilter = null;

            const runReport = async (filter) => {
                if (!selectedReport) {
                    status.textContent =
                        "Select an aggregate report first.";
                    status.classList.add("sql-query-error");
                    return;
                }
                buttons.forEach((button) => {
                    button.disabled = true;
                });
                section.querySelectorAll(
                    "[data-report-action]"
                ).forEach((button) => {
                    button.disabled = true;
                });
                section.setAttribute("aria-busy", "true");
                output.hidden = true;
                status.classList.remove("sql-query-error");
                status.textContent = filter
                    ? "Filtering aggregate report..."
                    : "Loading aggregate report...";
                header.replaceChildren();
                body.replaceChildren();
                emptyMessage.hidden = true;

                try {
                    const result = await this.executeAggregateReport(
                        table,
                        selectedReport,
                        filter
                    );
                    const headerRow = document.createElement("tr");
                    for (const column of result.columns) {
                        const cell = document.createElement("th");
                        cell.scope = "col";
                        cell.textContent = column;
                        headerRow.append(cell);
                    }
                    header.append(headerRow);

                    for (const row of result.rows) {
                        const resultRow = document.createElement("tr");
                        for (const value of row) {
                            const cell = document.createElement("td");
                            cell.textContent = value ?? "—";
                            resultRow.append(cell);
                        }
                        body.append(resultRow);
                    }

                    output.hidden = false;
                    emptyMessage.hidden = result.rows.length > 0;
                    appliedFilter = filter;
                    status.textContent = result.message
                        || `${result.rows.length} report row(s) returned.`;
                } catch (error) {
                    status.textContent = error.message;
                    status.classList.add("sql-query-error");
                } finally {
                    section.removeAttribute("aria-busy");
                    buttons.forEach((button) => {
                        button.disabled = false;
                    });
                    section.querySelectorAll(
                        "[data-report-action]"
                    ).forEach((button) => {
                        button.disabled = false;
                    });
                }
            };

            for (const button of buttons) {
                button.addEventListener("click", () => {
                    selectedReport = button.dataset.reportId;
                    filterInput.value = "";
                    appliedFilter = null;
                    filterLabel.textContent =
                        button.dataset.filterLabel;
                    filterInput.placeholder =
                        button.dataset.filterPlaceholder;
                    buttons.forEach((reportButton) => {
                        reportButton.setAttribute(
                            "aria-pressed",
                            String(reportButton === button)
                        );
                    });
                    runReport(null);
                });
            }

            section.querySelector(
                "[data-report-filter-button]"
            ).addEventListener("click", () => {
                const filter = filterInput.value.trim();
                if (!filter) {
                    status.textContent =
                        "Enter a filter value, or click Show All.";
                    status.classList.add("sql-query-error");
                    filterInput.focus();
                    return;
                }
                runReport(filter);
            });

            section.querySelector(
                "[data-report-show-all]"
            ).addEventListener("click", () => {
                filterInput.value = "";
                runReport(null);
            });

            section.querySelector(
                "[data-report-refresh]"
            ).addEventListener("click", () => {
                runReport(appliedFilter);
            });
        }
    }
});

document.addEventListener("DOMContentLoaded", () => {
    window.placementApi.initializeSqlQueries();
    window.placementApi.initializeAggregateReports();
});
