/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.protocols.docker.shared.tag.dtos;

import java.util.List;

/**
 * One page of the tags of an image, in the lexical order the distribution spec asks for.
 *
 * @param tags the tag names of the page
 * @param hasMore whether tags come after the last one of the page, so a {@code Link} to the next
 *     page is due
 */
public record TagPage(List<String> tags, boolean hasMore) {}
