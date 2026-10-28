window.placementApi = Object.freeze({
    async request(resource, options = {}, id = null) {
        const suffix = id === null
            ? ""
            : "/" + encodeURIComponent(id);
        const response = await fetch(
            new URL(`api/${resource}${suffix}`, window.location.href),
            {
                ...options,
                headers: {
                    "Content-Type": "application/json",
                    ...options.headers
                }
            }
        );
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
    }
});
