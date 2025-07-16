package com.sms;

import static java.lang.Character.*;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Collections.emptyMap;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.collections4.MapUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.*;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.annotation.Nonnull;
import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.messageresolver.StandardMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "api2html", mixinStandardHelpOptions = true)
@Slf4j
public class Api2Html implements Callable<Integer> {
	private static final String SCM_PREFIX = "#/components/schemas/";

	public static void main(String[] args) {
		try {
			new CommandLine(Api2Html.class).execute(args);
		} catch (final RuntimeException e) {
			LOG.error("api2html", e);
		}
	}

	private final TemplateEngine engine;

	@Option(names = {"-o", "--output"}, paramLabel = "OUTPUT")
	private File output;

	@Option(names = {"--no-description"})
	private boolean noDescription;

	@Parameters(index = "0", arity = "1")
	private File input;

	@Parameters(index = "1..*", arity = "0..*")
	private final Set<String> selection = new LinkedHashSet<>();

	private final Set<String> processed = new TreeSet<>();
	private Map<String, Schema> schemas;

	private PrintWriter writer;

	public Api2Html() {
		this.engine = new TemplateEngine();

		final var templateResolver = new ClassLoaderTemplateResolver(getClass().getClassLoader());

		templateResolver.setSuffix(".html");
		templateResolver.setTemplateMode(TemplateMode.HTML);

		this.engine.setTemplateResolver(templateResolver);
		this.engine.setMessageResolver(new StandardMessageResolver());
	}

	@Override
	public Integer call() throws Exception {
		final var p = new OpenAPIV3Parser();
		final var api = p.read(this.input.getAbsolutePath());

		this.schemas = ofNullable(api.getComponents())
				.map(Components::getSchemas)
				.orElse(emptyMap());

		for (final var ent : this.schemas.entrySet()) {
			final var id = ent.getKey();
			final var scm = ent.getValue();

			if (isEmpty(scm.get$id())) {
				scm.set$id(id);
			}

			setTitle(id, scm);
		}

		this.schemas = resolveSchemas(this.schemas, false);

		if (this.selection.isEmpty()) {
			this.selection.addAll(this.schemas.keySet());
		}

		if (output != null) {
			writer = new PrintWriter(output);
		} else {
			writer = new PrintWriter(System.out);
		}

		writer.println(
				"""
						<!DOCTYPE html>
						<html>
						<style type="text/css">
						  .tg  {border-collapse:collapse;border-color:#ccc;border-spacing:0;}
						  .tg td{background-color:#fff;border-color:#ccc;border-style:solid;border-width:1px;color:#333;
						    font-family:Arial, sans-serif;font-size:14px;overflow:hidden;padding:10px 5px;word-break:normal;}
						  .tg th{background-color:#f0f0f0;border-color:#ccc;border-style:solid;border-width:1px;color:#333;
						    font-family:Arial, sans-serif;font-size:14px;font-weight:normal;overflow:hidden;padding:10px 5px;word-break:normal;}
						  .tg .tg-0lax{text-align:left;vertical-align:top}
						</style>
						<body>
						""");

		for (final var id : this.selection) {
			final var scm = this.schemas.get(id);

			if (scm == null) {
				LOG.warn("Cannot find schema {}", id);
			}

			generateFor(scm);
		}

		writer.println("""
				</body>
				</html>
				""");

		if (output != null) {
			writer.close();
		}

		return 0;
	}

	private void setTitle(final String id, final Schema scm) {
		if (scm instanceof StringSchema) {
			if (isNotEmpty(scm.getEnum())) {
				final var enumType = capitalize(id) + "Enum";
				final var enumValues = ((List<String>) scm.getEnum()).stream()
						.map(StringUtils::trimToNull)
						.filter(Objects::nonNull)
						.filter(s -> !"null".equals(s))
						.toList();

				if (enumValues.stream().anyMatch(not(this::isJavaIdentifier))) {
					scm.setTitle(format("ValuedEnum<%s>", enumType));
				} else {
					scm.setTitle(enumType);
				}
				scm.setExtensions(Map.of("enums", join(", ", enumValues)));
			} else {
				scm.setTitle("String");
			}

			return;
		}

		if (scm instanceof BooleanSchema) {
			scm.setTitle("boolean");

			return;
		}

		if (isEmpty(scm.getTitle())) {
			scm.setTitle(capitalize(id).replace("-", "").replace("_", ""));
		}
	}

	private Map<String, Schema> resolveSchemas(Map<String, Schema> source, boolean fields) {
		final Map<String, Schema> target = new TreeMap<>();

		for (final var ent : source.entrySet()) {
			final var scm = resolveSchema(ent.getValue());

			cleanup(ent.getKey(), scm);

			if (fields) {
				target.put(uncapitalize(ent.getKey()), scm);
			} else {
				target.put(capitalize(ent.getKey()), scm);
			}
		}

		return target;
	}

	private List<Schema> resolveSchemas(List<Schema> source) {
		final List<Schema> target = new ArrayList<>();

		for (final var scm : source) {
			target.add(resolveSchema(scm));
		}

		return target;
	}

	private Schema<?> resolveSchema(@NonNull @Nonnull Schema<?> scm) {
		final var $ref = scm.get$ref();

		if (isNotEmpty($ref)) {
			scm = this.schemas.get(refToId($ref));

			if (scm == null) {
				throw new Api2HtmlException(format("Cannot find reference %s", $ref));
			}
		}

		return scm;
	}

	private void cleanup(String id, Schema schema) {
		if (schema instanceof ArraySchema a) {
			schema.setItems(resolveSchema(schema.getItems()));
		}

		if (schema instanceof ComposedSchema && isNotEmpty(schema.getOneOf())) {
			schema.setOneOf(resolveSchemas(schema.getOneOf()));

			return;
		}

		if (schema instanceof ObjectSchema && isNotEmpty(schema.getProperties())) {
			schema.setProperties(resolveSchemas(schema.getProperties(), true));

			return;
		}

		setTitle(id, schema);
	}

	private void generateFor(@Nonnull Schema schema) {
		if (schema instanceof ArraySchema a) {
			generateFor(schema.getItems());
		}

		if (schema instanceof ComposedSchema && isNotEmpty(schema.getOneOf())) {
			render("composed", schema);

			for (final Schema child : (List<Schema>) schema.getOneOf()) {
				generateFor(child);
			}

			return;
		}

		if (schema instanceof ObjectSchema && isNotEmpty(schema.getProperties())) {
			render("object", schema);

			for (final var child : (Collection<Schema>) schema.getProperties().values()) {
				generateFor(child);
			}

			return;
		}
	}

	private void render(String template, Schema schema) {
		final var id = schema.get$id();

		if (!this.processed.add(id)) {
			LOG.info("Schema {} rendered already", id);
			return;
		}

		final var context = new Context();

		context.setVariable("schema", schema);
		context.setVariable("description", !this.noDescription);

		this.engine.process(template, context, writer);
	}

	private String refToId(final String ref) {
		if (!ref.startsWith(SCM_PREFIX)) {
			throw new IllegalArgumentException(format("Value \"%s\" is not a ref", ref));
		}

		return ref.substring(SCM_PREFIX.length());
	}

	private boolean isJavaIdentifier(String s) {
		if (isEmpty(s)) {
			return false;
		}

		char[] chars = s.toCharArray();

		if (!isJavaIdentifierStart(chars[0]) || chars[0] == '_') {
			return false;
		}

		for (int k = 1; k < chars.length; k++) {
			if (!isJavaIdentifierPart(chars[k])) {
				return false;
			}
		}

		return true;
	}
}
