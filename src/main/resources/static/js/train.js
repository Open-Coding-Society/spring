async function getCompany() {
    let dataOut;
    await fetch("/api/train/get/company", {
        method: "GET",
        cache: "no-cache",
    }).then((response) => response.json()).then((data) => {
        dataOut = data;
    })
    return dataOut;
}

async function getTrains() {
    let dataOut;
    await fetch("/api/train/get/trains", {
        method: "GET",
        cache: "no-cache",
    }).then((response) => response.json()).then((data) => {
        dataOut = data;
    })
    return dataOut;
}

async function getStation() {
    let dataOut;
    await fetch("/api/train/get/station", {
        method: "GET",
        cache: "no-cache",
    }).then((response) => response.json()).then((data) => {
        dataOut = data;
    })
    return dataOut;
}

function fillCompanyHtml(container,companyInfo){
    console.log(companyInfo);
    let h1 = document.createElement("h1");
    h1.innerText = companyInfo.companyName;

    let div = document.createElement("div");
    let a = document.createElement("a");
    a.innerText = "owner";
    a.setAttribute("href","/mvc/person/read/"+companyInfo.id);

    div.append(a);

    container.append(h1);
    container.append(div);
}

function fillTrainsHtml(container,trainsInfo){
    console.log(trainsInfo);
    let table = document.createElement("table");
    let tableHead = document.createElement("thead");
    let tableHeadRow = document.createElement("tr");
    let tableHeader1 = document.createElement("th");
    let tableHeader2 = document.createElement("th");
    tableHeader1.innerText = "id";
    tableHeader2.innerText = "position";
    tableHeadRow.append(tableHeader1);
    tableHeadRow.append(tableHeader2);
    tableHead.append(tableHeadRow);
    table.append(tableHead);

    let tableBody = document.createElement("tbody");
    for(let i=0; i<trainsInfo.length; i++){
        let tableBodyRow = document.createElement("tr");
        let tableRowDetail1 = document.createElement("td");
        let tableRowDetail2 = document.createElement("td");
        tableRowDetail1.innerText = trainsInfo[i].id;
        tableRowDetail2.innerText = trainsInfo[i].position;
        tableBodyRow.append(tableRowDetail1);
        tableBodyRow.append(tableRowDetail2);
        tableBody.append(tableBodyRow);
    }
    table.append(tableBody);

    container.append(table);
}

async function setUp(){
    let company = await getCompany();
    let trains = await getTrains();
    let station = await getStation();

    fillCompanyHtml(document.getElementById("companyContainer"),company);
    fillTrainsHtml(document.getElementById("trainsContainer"),trains);
    console.log(station);
}

setUp();