CREATE TABLE WALLET_ACCOUNT (USER_DNI VARCHAR(255), JAVA_COINS DOUBLE);
CREATE TABLE ORDERS (
    ID BIGINT PRIMARY KEY,
    SELLER_DNI VARCHAR(255),
    BUYER_DNI VARCHAR(255),
    JAVA_COINS_AMOUNT DOUBLE,
    JAVA_COIN_PRICE DOUBLE,
    ORDER_STATE VARCHAR(255)
);
