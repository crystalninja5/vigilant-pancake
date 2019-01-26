/*

    Copyright 2018-2019 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.rest.spring.serializers;

import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.util.Utility;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class HttpConverterText implements HttpMessageConverter<Object> {

    private static final Utility util = Utility.getInstance();
    private static final MediaType TEXT_CONTENT = new MediaType("text", "plain", Charset.forName("UTF-8"));
    private static List<MediaType> types = new ArrayList<>();

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return mediaType != null && TEXT_CONTENT.getType().equals(mediaType.getType())
                && TEXT_CONTENT.getSubtype().equals(mediaType.getSubtype());
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return mediaType != null && TEXT_CONTENT.getType().equals(mediaType.getType())
                && TEXT_CONTENT.getSubtype().equals(mediaType.getSubtype());
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        if (types.isEmpty()) {
            types.add(TEXT_CONTENT);
        }
        return types;
    }

    @Override
    public Object read(Class<?> clazz, HttpInputMessage inputMessage) throws HttpMessageNotReadableException, IOException {
        return inputMessage != null? inputMessage.getBody() : null;
    }

    @Override
    public void write(Object o, MediaType contentType, HttpOutputMessage outputMessage) throws HttpMessageNotWritableException, IOException {
        outputMessage.getHeaders().setContentType(TEXT_CONTENT);
        OutputStream out = outputMessage.getBody();
        if (o instanceof String) {
            out.write(util.getUTF((String) o));
        } else if (o instanceof byte[]) {
            out.write((byte[]) o);
        } else {
            out.write(SimpleMapper.getInstance().getMapper().writeValueAsBytes(o));
        }
    }

}
