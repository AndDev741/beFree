package org.beFree.category;

import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CategoryResource {

    @GET
    public List<Category> list() {
        return Category.listAll(Sort.by("name"));
    }

    @POST
    @Transactional
    public RestResponse<Category> create(Category category) {
        if (category == null || category.name == null || category.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        category.id = null;
        category.persist();
        return RestResponse.status(RestResponse.Status.CREATED, category);
    }
}
