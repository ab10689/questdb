/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.client;

public class HttpClientMain {
    public static void main(String[] args) {

        try (HttpClient client = HttpClientFactory.newInstance()) {

            for (int i = 0; i < 10; i++) {
                HttpClient.Request req = client.newRequest();

                HttpClient.Response rsp = req
                        .GET()
                        .url("/exec")
                        .query("query", "cpu%20limit%20400000")
//                .query("query", "cpu")
                        .header("Accept", "gzip, deflate, br")
                        .send("localhost", 9000);

                rsp.awaitHeaders();

                if (rsp.isChunked()) {
                    HttpClient.Response.Chunk chunk;

                    long t = System.currentTimeMillis();
                    int chunkCount = 0;
                    while ((chunk = rsp.recv()) != null) {
//                        System.out.println("addr: " + chunk.addr + ", size: " + chunk.size + ", consumed: " + chunk.consumed + ", available: " + chunk.available);
                        chunkCount++;
                    }
                    System.out.println(System.currentTimeMillis() - t);
                    System.out.println("done: " + i +", chunks: "+chunkCount);
                }

            }
        }
    }
}
