/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Fire me up!
'use strict';

module.exports = {
    implements: 'configure',
    inject: ['require(morgan)', 'require(cookie-parser)', 'require(body-parser)', 'require(express-force-ssl)',
        'require(express-session)', 'require(connect-mongo)', 'require(passport)', 'settings', 'mongo']
};

module.exports.factory = function(logger, cookieParser, bodyParser, forceSSL, session, connectMongo, passport, settings, mongo) {
    return (app) => {
        app.use(logger('dev', {
            skip: function(req, res) {
                return res.statusCode < 400;
            }
        }));

        app.use(cookieParser(settings.sessionSecret));

        app.use(bodyParser.json({limit: '50mb'}));
        app.use(bodyParser.urlencoded({limit: '50mb', extended: true}));

        const mongoStore = connectMongo(session);

        app.use(session({
            secret: settings.sessionSecret,
            resave: false,
            saveUninitialized: true,
            cookie: {
                expires: new Date(Date.now() + settings.cookieTTL),
                maxAge: settings.cookieTTL
            },
            store: new mongoStore({mongooseConnection: mongo.connection})
        }));

        app.use(passport.initialize());
        app.use(passport.session());

        passport.serializeUser(mongo.Account.serializeUser());
        passport.deserializeUser(mongo.Account.deserializeUser());

        passport.use(mongo.Account.createStrategy());

        if (settings.SSLOptions) {
            app.set('forceSSLOptions', settings.SSLOptions);

            app.use(forceSSL);
        }
    };
};